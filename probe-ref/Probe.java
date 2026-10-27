package com.simon.lenslock;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Round three: single session per lens, streaming preview frames, zoom ladder.
 * Answers (a) does digital zoom still work on a pinned physical sensor and
 * (b) how much wider is the pinned ultrawide than the pinned main.
 * Frames are captured from the live repeating request, so nothing floods.
 */
public class Probe {

    static final String TAG = "LensLockProbe";

    private final Context ctx;
    private final File outDir;
    private final StringBuilder sb = new StringBuilder();

    private HandlerThread thread;
    private Handler handler;
    private CameraManager mgr;

    private String backId;
    private CameraCharacteristics backChars;
    private String physUw;
    private String physMain;
    private float minZoom = 1f;

    private volatile byte[] latest;
    private volatile int latestW, latestH;
    private volatile String latestResult;

    public Probe(Context c) {
        ctx = c;
        File ext = c.getExternalFilesDir(null);
        outDir = (ext != null) ? ext : c.getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
    }

    public String run() {
        String summary;
        try {
            thread = new HandlerThread("probe-camera");
            thread.start();
            handler = new Handler(thread.getLooper());
            mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            log("LensLock probe round 3 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            describeCameras();
            if (physUw == null) return "no ultrawide";

            ladder("R1_pinUW", physUw, new float[]{1.0f, 1.5f, 2.0f, 3.0f});
            ladder("R2_pinMAIN", physMain, new float[]{1.0f, 2.0f});
            ladder("R3_logical", null, new float[]{minZoom, 1.0f, 2.0f});

            summary = "round 3 finished, report at " + new File(outDir, "report3.txt");
        } catch (Throwable t) {
            log("PROBE FAILED: " + t);
            summary = "probe failed: " + t;
        } finally {
            flush();
            if (thread != null) thread.quitSafely();
        }
        return summary;
    }

    private void describeCameras() throws Exception {
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) {
                backId = id;
                backChars = c;
            }
        }
        Range<Float> zr = backChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null) minZoom = zr.getLower();
        log("back id " + backId + " zoom " + zr);
        float best = Float.MAX_VALUE;
        for (String p : backChars.getPhysicalCameraIds()) {
            float[] pf = mgr.getCameraCharacteristics(p).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float focal = (pf != null && pf.length > 0) ? pf[0] : 0f;
            if (focal > 0 && focal < best) { best = focal; physUw = p; }
            if (focal >= 4f && physMain == null) physMain = p;
            log("  physical " + p + " focal=" + focal);
        }
        log("ultrawide=" + physUw + " main=" + physMain);
    }

    private void ladder(String name, String phys, float[] zooms) {
        log("");
        log("=== LADDER " + name + "  pin=" + (phys == null ? "logical" : phys) + "  zooms=" + Arrays.toString(zooms));
        CameraDevice dev = null;
        ImageReader reader = null;
        CameraCaptureSession session = null;
        try {
            dev = openDevice();
            Size size = sizeFor(1280, 960, ImageFormat.YUV_420_888);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireNextImage();
                if (img == null) return;
                try {
                    latest = yuv420ToNv21(img);
                    latestW = img.getWidth();
                    latestH = img.getHeight();
                } catch (Throwable t) {
                    log("  frame read error: " + t);
                } finally {
                    img.close();
                }
            }, handler);
            session = createSession(dev, reader.getSurface(), phys);

            for (float zoom : zooms) {
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(reader.getSurface());
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
                latest = null;
                session.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                        latestResult = describe(res);
                    }
                }, handler);
                for (int i = 0; i < 30 && latest == null; i++) Thread.sleep(100);
                Thread.sleep(900);
                byte[] frame = latest;
                if (frame == null) {
                    log("  zoom " + zoom + ": no frame");
                    continue;
                }
                String tag = name + "_z" + String.valueOf(zoom).replace('.', 'p');
                File f = new File(outDir, tag + ".jpg");
                saveJpeg(frame, f, (int) zoom, latestW, latestH, tag);
                log("  zoom " + zoom + " -> " + f.getName() + " " + f.length() + " bytes");
                log("    result: " + latestResult);
            }
        } catch (Exception e) {
            log("  LADDER FAILED: " + e);
        } finally {
            if (session != null) session.close();
            if (dev != null) dev.close();
            if (reader != null) reader.close();
        }
        flush();
    }

    private void saveJpeg(byte[] nv21, File f, int quality, int w, int h, String tag) {
        try {
            android.graphics.YuvImage yi = new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null);
            android.graphics.Rect rect = new android.graphics.Rect(0, 0, w, h);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                yi.compressToJpeg(rect, 95, fos);
            }
        } catch (Throwable t) {
            log("  save failed: " + t);
        }
    }

    private StreamConfigurationMap sizeMap() {
        return backChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    }

    private Size sizeFor(int w, int h, int format) {
        Size[] sizes = sizeMap().getOutputSizes(format);
        if (sizes == null) return new Size(w, h);
        for (Size s : sizes) if (s.getWidth() == w && s.getHeight() == h) return s;
        return sizes[0];
    }

    private CameraDevice openDevice() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final CameraDevice[] holder = new CameraDevice[1];
        final String[] error = new String[1];
        mgr.openCamera(backId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice camera) { holder[0] = camera; latch.countDown(); }
            @Override public void onDisconnected(CameraDevice camera) { error[0] = "disconnected"; camera.close(); latch.countDown(); }
            @Override public void onError(CameraDevice camera, int code) { error[0] = "error " + code; camera.close(); latch.countDown(); }
        }, handler);
        if (!latch.await(8, TimeUnit.SECONDS) || holder[0] == null) {
            throw new IOException("openCamera failed: " + (error[0] == null ? "timeout" : error[0]));
        }
        return holder[0];
    }

    @SuppressWarnings("deprecation")
    private CameraCaptureSession createSession(CameraDevice dev, android.view.Surface surface, String phys) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final List<CameraCaptureSession> holder = Collections.synchronizedList(new ArrayList<>());
        final String[] error = new String[1];
        CameraCaptureSession.StateCallback cb = new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession session) { holder.add(session); latch.countDown(); }
            @Override public void onConfigureFailed(CameraCaptureSession session) { error[0] = "onConfigureFailed"; latch.countDown(); }
        };
        if (phys == null) {
            dev.createCaptureSession(Collections.singletonList(surface), cb, handler);
        } else {
            OutputConfiguration oc = new OutputConfiguration(surface);
            oc.setPhysicalCameraId(phys);
            dev.createCaptureSessionByOutputConfigurations(Collections.singletonList(oc), cb, handler);
        }
        if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("session config timeout");
        if (holder.isEmpty()) throw new IOException("session config failed: " + error[0]);
        return holder.get(0);
    }

    private String describe(TotalCaptureResult res) {
        StringBuilder b = new StringBuilder();
        b.append("activePhys=").append(res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID));
        b.append(" focal=").append(res.get(CaptureResult.LENS_FOCAL_LENGTH));
        b.append(" zoom=").append(res.get(CaptureResult.CONTROL_ZOOM_RATIO));
        b.append(" crop=").append(res.get(CaptureResult.SCALER_CROP_REGION));
        try {
            Map<String, CaptureResult> per = res.getPhysicalCameraResults();
            if (per != null && !per.isEmpty()) {
                b.append(" | physical:");
                for (Map.Entry<String, CaptureResult> e : per.entrySet()) {
                    b.append(" [").append(e.getKey()).append(" focal=").append(e.getValue().get(CaptureResult.LENS_FOCAL_LENGTH))
                            .append(" crop=").append(e.getValue().get(CaptureResult.SCALER_CROP_REGION)).append("]");
                }
            }
        } catch (Throwable ignored) {
        }
        return b.toString();
    }

    private static byte[] yuv420ToNv21(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] out = new byte[w * h * 3 / 2];
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yBuf = p[0].getBuffer();
        int yRow = p[0].getRowStride();
        int yPix = p[0].getPixelStride();
        int pos = 0;
        for (int row = 0; row < h; row++) {
            int base = row * yRow;
            for (int col = 0; col < w; col++) out[pos++] = yBuf.get(base + col * yPix);
        }
        ByteBuffer uBuf = p[1].getBuffer();
        ByteBuffer vBuf = p[2].getBuffer();
        int uvRow = p[1].getRowStride();
        int uvPix = p[1].getPixelStride();
        for (int row = 0; row < h / 2; row++) {
            int base = row * uvRow;
            for (int col = 0; col < w / 2; col++) {
                out[pos++] = vBuf.get(base + col * uvPix);
                out[pos++] = uBuf.get(base + col * uvPix);
            }
        }
        return out;
    }

    private void log(String line) {
        sb.append(line).append('\n');
        android.util.Log.i(TAG, line);
    }

    private void flush() {
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, "report3.txt"))) {
            fos.write(sb.toString().getBytes());
        } catch (IOException e) {
            android.util.Log.e(TAG, "report write failed", e);
        }
    }
}
