package com.simon.lenslock;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the camera. One pinned physical sensor per session, always. Lens changes
 * rebuild the session because zoom and the physical id are fixed at configure time.
 */
public class CameraEngine {

    static final String TAG = "LensLock";
    private static final String PREFS = "lenslock";
    private static final String PREF_PHYSICAL_ID = "last_physical_id";

    public interface Listener {
        void onReady(List<Lens> lenses, Lens selected, Size previewSize, float maxFocusDiopters, float evStepValue, int evMin, int evMax, int sensorOrientation);
        void onSessionReady(Lens lens, boolean previewPinned);
        void onSaved(Uri uri, String detail);
        void onError(String message);
        void onStatus(String text);
    }

    public static class Lens {
        public final String physicalId;
        public final float focalMm;
        public final boolean ultrawide;
        public final float minFocusDiopters;

        Lens(String physicalId, float focalMm, boolean ultrawide, float minFocusDiopters) {
            this.physicalId = physicalId;
            this.focalMm = focalMm;
            this.ultrawide = ultrawide;
            this.minFocusDiopters = minFocusDiopters;
        }

        public String name() {
            return ultrawide ? "Ultra" : "Main";
        }

        public String spec() {
            return String.format(Locale.US, "%s %.2fmm", name(), focalMm);
        }

        public String closeFocus() {
            return minFocusDiopters > 0.01f
                    ? String.format(Locale.US, "%.0fcm", 100f / minFocusDiopters)
                    : "?";
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler mainHandler;
    private final HandlerThread camThread;
    private final Handler camHandler;
    private final HandlerThread cbThread;
    private final Handler cbHandler;
    private final ExecutorService saver = Executors.newSingleThreadExecutor();

    private CameraManager mgr;
    private String cameraId;
    private CameraCharacteristics chars;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader stillReader;
    private Surface previewSurface;

    private final List<Lens> lenses = new ArrayList<>();
    private Lens lens;
    private Size previewSize;
    private Size stillSize;
    private Rect activeArray = new Rect(0, 0, 4000, 3000);
    private int sensorOrientation = 90;
    private int displayRotationDegrees = 0;
    private int previewRotationDegrees = 90;
    private Range<Integer> evRange;
    private float evStep = 1f;
    private int evIndex;
    private float zoom = 1f;
    private boolean manualFocus;
    private float manualFocusDiopters;
    private int afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    private MeteringRectangle[] afRegions;
    private MeteringRectangle[] aeRegions;
    private boolean building;
    private float pendingZoom = 1f;
    private int shotCount;

    public CameraEngine(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.camThread = new HandlerThread("lenslock-cam");
        this.camThread.start();
        this.camHandler = new Handler(camThread.getLooper());
        this.cbThread = new HandlerThread("lenslock-cb");
        this.cbThread.start();
        this.cbHandler = new Handler(cbThread.getLooper());
        camHandler.post(this::setup);
    }

    // ------------------------------------------------------------------- setup

    private void setup() {
        try {
            mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    chars = c;
                    break;
                }
            }
            if (chars == null) {
                postError("no rear camera");
                return;
            }

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 1920);
            stillSize = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4096);

            Rect crop = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (crop != null) activeArray = crop;
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensorOrientation = so;
            evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (step != null) evStep = step.floatValue();

            buildLensList();

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String wanted = prefs.getString(PREF_PHYSICAL_ID, null);
            for (Lens l : lenses) {
                if (l.physicalId != null && l.physicalId.equals(wanted)) {
                    lens = l;
                    break;
                }
            }
            if (lens == null) lens = defaultLens();

            Log.i(TAG, "setup camera=" + cameraId + " lenses=" + lenses.size() + " preview=" + previewSize
                    + " still=" + stillSize + " lens=" + lens.spec() + " id=" + lens.physicalId
                    + " evStep=" + evStep + " evRange=" + evRange);

            float maxFocus = 0f;
            for (Lens l : lenses) maxFocus = Math.max(maxFocus, l.minFocusDiopters);
            final Lens initial = lens;
            final float maxF = maxFocus;
            final int evMin = evRange != null ? evRange.getLower() : -6;
            final int evMax = evRange != null ? evRange.getUpper() : 6;
            mainHandler.post(() -> listener.onReady(new ArrayList<>(lenses), initial, previewSize, maxF, evStep, evMin, evMax, sensorOrientation));
        } catch (Exception e) {
            postError("setup failed: " + e);
        }
    }

    private void buildLensList() throws Exception {
        List<Lens> found = new ArrayList<>();
        for (String p : chars.getPhysicalCameraIds()) {
            CameraCharacteristics pc = mgr.getCameraCharacteristics(p);
            float[] focals = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focals == null || focals.length == 0) continue;
            Float minFocus = pc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            found.add(new Lens(p, focals[0], false, minFocus == null ? 0f : minFocus));
        }
        found.sort(Comparator.comparing((Lens l) -> l.focalMm).thenComparing(l -> l.physicalId));

        // one entry per distinct focal length, lowest id wins (Pixel exposes the
        // main sensor twice, once per readout mode)
        List<Lens> unique = new ArrayList<>();
        for (Lens l : found) {
            boolean dup = false;
            for (Lens u : unique) {
                if (Math.abs(u.focalMm - l.focalMm) < 0.05f) {
                    dup = true;
                    break;
                }
            }
            if (!dup) unique.add(l);
        }
        if (unique.isEmpty()) {
            float[] focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            unique.add(new Lens(null, focals != null && focals.length > 0 ? focals[0] : 0f, false,
                    minFocus == null ? 0f : minFocus));
        }

        float widest = unique.get(0).focalMm;
        float longest = unique.get(unique.size() - 1).focalMm;
        List<Lens> two = new ArrayList<>();
        for (Lens l : unique) {
            boolean uw = unique.size() > 1 && l.focalMm < longest * 0.75f;
            two.add(new Lens(l.physicalId, l.focalMm, uw, l.minFocusDiopters));
        }
        if (two.size() > 2) {
            List<Lens> trimmed = new ArrayList<>();
            trimmed.add(two.get(0));
            trimmed.add(two.get(two.size() - 1));
            two = trimmed;
        }
        lenses.clear();
        lenses.addAll(two);
        for (Lens l : lenses) {
            Log.i(TAG, "lens " + l.spec() + " id=" + l.physicalId + " closeFocus=" + l.closeFocus()
                    + " widest=" + widest + " longest=" + longest);
        }
    }

    private Lens defaultLens() {
        for (Lens l : lenses) {
            if (!l.ultrawide) return l;
        }
        return lenses.get(0);
    }

    private Size pickSize(Size[] sizes, int maxWidth) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 960);
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > maxWidth) continue;
            float aspect = (float) s.getWidth() / s.getHeight();
            if (Math.abs(aspect - 4f / 3f) > 0.02f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() > maxWidth) continue;
                if (best == null || s.getWidth() > best.getWidth()) best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    // --------------------------------------------------------------- lifecycle

    public void setPreviewSurface(Surface surface) {
        camHandler.post(() -> {
            previewSurface = surface;
            requestSession();
        });
    }

    private void requestSession() {
        if (previewSurface == null || lens == null) return;
        if (device == null) {
            openDevice();
        } else {
            buildSession();
        }
    }

    private void openDevice() {
        try {
            Log.i(TAG, "opening camera " + cameraId);
            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    camHandler.post(CameraEngine.this::buildSession);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    device = null;
                    postError("camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int code) {
                    camera.close();
                    device = null;
                    postError("camera error " + code);
                }
            }, cbHandler);
        } catch (Exception e) {
            postError("open failed: " + e.getMessage());
        }
    }

    private void buildSession() {
        if (building) return;
        if (device == null || previewSurface == null || lens == null) return;
        building = true;
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            createStillReader();
            boolean pinnedPreview = lens.physicalId != null && configure(true);
            if (!pinnedPreview) {
                boolean pinnedStillOnly = lens.physicalId != null && configure(false);
                if (!pinnedStillOnly) {
                    postError("could not start a session for " + lens.spec());
                    return;
                }
                Log.w(TAG, "preview could not be pinned, still capture is pinned");
            }
            final boolean previewPinned = pinnedPreview;
            final Lens active = lens;
            mainHandler.post(() -> listener.onSessionReady(active, previewPinned));
        } catch (Exception e) {
            Log.e(TAG, "buildSession failed", e);
            postError("session failed: " + e.getMessage());
        } finally {
            building = false;
        }
    }

    private void createStillReader() {
        Size size = stillSizeFor(lens);
        if (stillReader != null) {
            if (size.equals(stillSize) && !stillReader.getSurface().isValid()) {
                // unusable, fall through and recreate
            }
            stillReader.close();
            stillReader = null;
        }
        stillSize = size;
        stillReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
        stillReader.setOnImageAvailableListener(this::onImageAvailable, cbHandler);
        Log.i(TAG, "still reader " + size + " for " + lens.spec());
    }

    private Size stillSizeFor(Lens l) {
        try {
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size logical = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4096);
            if (l != null && l.physicalId != null) {
                CameraCharacteristics pc = mgr.getCameraCharacteristics(l.physicalId);
                StreamConfigurationMap pmap = pc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size physical = pickSize(pmap.getOutputSizes(ImageFormat.JPEG), 4096);
                if (physical != null && physical.getWidth() * physical.getHeight() > logical.getWidth() * logical.getHeight()) {
                    return physical;
                }
                return physical != null ? physical : logical;
            }
            return logical;
        } catch (Exception e) {
            Log.w(TAG, "stillSizeFor failed: " + e);
            return stillSize != null ? stillSize : new Size(1920, 1440);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean configure(boolean pinPreview) throws Exception {
        List<OutputConfiguration> outputs = new ArrayList<>();
        OutputConfiguration previewOut = new OutputConfiguration(previewSurface);
        if (pinPreview && lens.physicalId != null) previewOut.setPhysicalCameraId(lens.physicalId);
        outputs.add(previewOut);

        OutputConfiguration stillOut = new OutputConfiguration(stillReader.getSurface());
        if (lens.physicalId != null) stillOut.setPhysicalCameraId(lens.physicalId);
        outputs.add(stillOut);

        final CountDownLatch latch = new CountDownLatch(1);
        final CameraCaptureSession[] holder = new CameraCaptureSession[1];
        final String[] error = new String[1];
        device.createCaptureSessionByOutputConfigurations(outputs, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                holder[0] = s;
                latch.countDown();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                error[0] = "configure failed";
                latch.countDown();
            }
        }, cbHandler);

        if (!latch.await(6, TimeUnit.SECONDS)) throw new IOException("session configure timeout");
        if (holder[0] == null) {
            Log.w(TAG, "configure(pinPreview=" + pinPreview + ") -> " + error[0]);
            return false;
        }
        session = holder[0];
        startPreview();
        Log.i(TAG, "session ready lens=" + lens.spec() + " id=" + lens.physicalId + " pinnedPreview=" + pinPreview);
        return true;
    }

    private CaptureRequest.Builder newPreviewRequest() throws Exception {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(previewSurface);
        applyCommon(b);
        return b;
    }

    private void startPreview() {
        try {
            session.setRepeatingRequest(newPreviewRequest().build(), null, cbHandler);
        } catch (Exception e) {
            postError("preview failed: " + e.getMessage());
        }
    }

    private void updateRepeating() {
        if (session == null) return;
        try {
            session.setRepeatingRequest(newPreviewRequest().build(), null, cbHandler);
        } catch (Exception e) {
            Log.w(TAG, "updateRepeating failed: " + e);
        }
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (evRange != null) {
            int v = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), evIndex));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, v);
        }
        if (manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDiopters);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            if (afRegions != null) b.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
        }
        if (aeRegions != null) b.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions);
    }

    // ----------------------------------------------------------------- actions

    public void setLens(Lens next) {
        camHandler.post(() -> {
            if (next == null) return;
            if (lens != null && next.physicalId != null && next.physicalId.equals(lens.physicalId)) return;
            lens = next;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_PHYSICAL_ID, next.physicalId == null ? "" : next.physicalId).apply();
            requestSession();
        });
    }

    public List<Lens> lenses() {
        return new ArrayList<>(lenses);
    }

    public void setDisplayRotation(int degrees) {
        displayRotationDegrees = ((degrees % 360) + 360) % 360;
        Log.i(TAG, "display rotation " + displayRotationDegrees + " -> image rotation " + imageRotation());
    }

    /** Clockwise angle that makes the sensor frame upright for the current hold. */
    public int imageRotation() {
        return (sensorOrientation - displayRotationDegrees + 360) % 360;
    }

    /** Rotation the viewfinder is showing, used to map taps back onto the sensor. */
    public void setPreviewRotation(int degrees) {
        previewRotationDegrees = ((degrees % 360) + 360) % 360;
    }

    public void setZoom(float z) {
        zoom = z;
    }

    public float zoom() {
        return zoom;
    }

    public void setManualFocus(boolean manual, float diopters) {
        manualFocus = manual;
        manualFocusDiopters = diopters;
        camHandler.post(this::updateRepeating);
    }

    public void setExposureIndex(int index) {
        evIndex = index;
        camHandler.post(this::updateRepeating);
    }

    public float evStepValue() {
        return evStep;
    }

    public void tapFocus(float nx, float ny) {
        final float cx = 0.5f + (nx - 0.5f) / Math.max(zoom, 1f);
        final float cy = 0.5f + (ny - 0.5f) / Math.max(zoom, 1f);
        camHandler.post(() -> {
            if (manualFocus) return;
            // map the tap back through whatever rotation the viewfinder shows
            float u = cx;
            float v = cy;
            float sx;
            float sy;
            switch (previewRotationDegrees) {
                case 90:
                    sx = v;
                    sy = 1f - u;
                    break;
                case 180:
                    sx = 1f - u;
                    sy = 1f - v;
                    break;
                case 270:
                    sx = 1f - v;
                    sy = u;
                    break;
                default:
                    sx = u;
                    sy = v;
                    break;
            }
            int w = activeArray.width();
            int h = activeArray.height();
            int px = activeArray.left + Math.round(sx * w);
            int py = activeArray.top + Math.round(sy * h);
            int half = Math.max(w, h) / 12;
            Rect r = new Rect(px - half, py - half, px + half, py + half);
            Rect bounds = new Rect(activeArray.left, activeArray.top, activeArray.right, activeArray.bottom);
            if (!r.intersect(bounds)) return;
            MeteringRectangle mr = new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX);
            afRegions = new MeteringRectangle[]{mr};
            aeRegions = new MeteringRectangle[]{mr};
            afMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
            updateRepeating();
        });
    }

    public void capture() {
        camHandler.post(() -> {
            if (session == null) {
                postError("camera not ready");
                return;
            }
            try {
                pendingZoom = zoom;
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillReader.getSurface());
                applyCommon(b);
                b.set(CaptureRequest.JPEG_ORIENTATION, imageRotation());
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                        String meta = describe(result);
                        Log.i(TAG, "still " + meta);
                        postStatus(meta);
                    }

                    @Override
                    public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) {
                        postError("capture failed: " + f.getReason());
                    }
                }, cbHandler);
            } catch (Exception e) {
                postError("capture error: " + e.getMessage());
            }
        });
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireNextImage();
        if (image == null) return;
        final int iw = image.getWidth();
        final int ih = image.getHeight();
        byte[] jpeg;
        try {
            java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
        } finally {
            image.close();
        }
        final float z = pendingZoom;
        Log.i(TAG, "jpeg delivered " + iw + "x" + ih + " (" + jpeg.length + " bytes)");
        saver.execute(() -> {
            try {
                Uri uri = JpegSaver.save(ctx, jpeg, imageRotation(), z);
                shotCount++;
                mainHandler.post(() -> listener.onSaved(uri, "saved " + shotCount + "  zoom " + z
                        + "x  " + (lens != null ? lens.spec() : "")));
            } catch (Throwable t) {
                Log.e(TAG, "save failed", t);
                postError("save failed: " + t.getMessage());
            }
        });
    }

    private String describe(TotalCaptureResult res) {
        Object phys = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        Float focal = res.get(CaptureResult.LENS_FOCAL_LENGTH);
        Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        Float focusDist = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Integer afModeValue = res.get(CaptureResult.CONTROL_AF_MODE);
        StringBuilder b = new StringBuilder();
        b.append("sensor ").append(phys).append("  ").append(focal == null ? "?" : String.format(Locale.US, "%.2fmm", focal));
        if (exp != null) b.append("  ").append(String.format(Locale.US, "1/%.0f", 1e9 / Math.max(exp, 1)));
        if (iso != null) b.append("  ISO ").append(iso);
        if (afModeValue != null) b.append("  afMode ").append(afModeValue);
        Integer evValue = res.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (evValue != null) b.append("  evStep ").append(evValue);
        if (focusDist != null) {
            b.append("  focus ").append(focusDist > 0.01f
                    ? String.format(Locale.US, "%.0fcm", 100f / focusDist) : "inf");
        }
        try {
            Map<String, CaptureResult> per = res.getPhysicalCameraResults();
            if (per != null && !per.isEmpty()) {
                b.append("  physical ");
                for (String k : per.keySet()) b.append(k).append(" ");
            }
        } catch (Throwable ignored) {
        }
        return b.toString();
    }

    public void close() {
        camHandler.post(() -> {
            try {
                if (session != null) session.close();
                if (device != null) device.close();
                if (stillReader != null) stillReader.close();
            } catch (Exception ignored) {
            }
            session = null;
            device = null;
            stillReader = null;
            camThread.quitSafely();
            cbThread.quitSafely();
            saver.shutdown();
        });
    }

    private void postError(String message) {
        Log.e(TAG, message);
        mainHandler.post(() -> listener.onError(message));
    }

    private void postStatus(String text) {
        mainHandler.post(() -> listener.onStatus(text));
    }
}
