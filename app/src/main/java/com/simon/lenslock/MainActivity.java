package com.simon.lenslock;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CameraEngine.Listener {

    private static final int REQ_CAMERA = 1;

    private static final int BG = 0xFF0B0B0B;
    private static final int GLASS = 0xCC141414;
    private static final int GLASS_LIGHT = 0xE61C1C1C;
    private static final int HAIRLINE = 0x26FFFFFF;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xB3FFFFFF;
    private static final int ERROR = 0xFFFF9A8F;

    /** The ultrawide sees about twice the field of the main sensor, so 0.5x. */
    private static final float ULTRAWIDE_BASE_ZOOM = 0.5f;
    private static final float MAX_CROP = 4f;

    /**
     * The TextureView matrix runs in view space, so its rotation direction is
     * opposite to the clockwise rotation baked into the saved JPEG. This offset
     * lines the viewfinder up with the file.
     */
    private static final int PREVIEW_ROTATION_OFFSET = -90;

    private CameraEngine engine;
    private FrameLayout root;
    private TextureView preview;
    private SurfaceTexture surfaceTexture;
    private GestureDetector gestures;
    private FocusRing focusRing;
    private View scrim;

    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout lensPill;
    private ZoomSlider zoomSlider;
    private LinearLayout panel;
    private TextView segNormal;
    private TextView segMacro;
    private TextView statusPill;
    private TextView adjustButton;
    private TextView focusValue;
    private TextView brightnessValue;
    private TextView segAuto;
    private TextView segManual;
    private ScaleGestureDetector scaleDetector;
    private float pendingEquivalentZoom = -1f;
    private ShutterButton shutter;
    private ImageView thumb;
    private SeekBar focusSlider;
    private SeekBar brightnessSlider;

    private CameraEngine.Lens selectedLens;
    private android.util.Size previewSize;
    private boolean loggedPresentation;
    private boolean loggedMatrix;
    private int sensorOrientation = 90;
    private float zoom = 1f;
    private float evStep = 1f;
    private float maxFocusDiopters = 10f;
    private int evIndex = 0;
    private int evMin = -6;
    private int evMax = 6;
    private boolean manualFocus = false;
    private boolean panelOpen = false;
    private boolean landscape = false;
    private Uri lastPhoto;
    private Insets lastInsets = Insets.of(0, 0, 0, 0);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        preview = new TextureView(this);
        root.addView(preview, match());

        focusRing = new FocusRing(this);
        root.addView(focusRing, match());

        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM));

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (engine == null) return true;
                focusRing.show(e.getX(), e.getY());
                engine.tapFocus(e.getX() / Math.max(preview.getWidth(), 1),
                        e.getY() / Math.max(preview.getHeight(), 1));
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                applyZoom(zoom * detector.getScaleFactor());
                showZoomReadout();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                ui.removeCallbacksAndMessages(null);
                ui.postDelayed(() -> statusPill.animate().alpha(0f).setDuration(220).start(), 900);
            }
        });
        preview.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (!scaleDetector.isInProgress()) gestures.onTouchEvent(event);
            return true;
        });
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surfaceTexture = st;
                attachPreview();
                configureTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                configureTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (!loggedPresentation) {
                    loggedPresentation = true;
                    float[] dims = presentedDims();
                    Log.i("LensLock", "preview presentation " + dims[0] + "x" + dims[1]
                            + " requested " + previewSize);
                    configureTransform();
                }
            }
        });

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            lastInsets = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            applyInsets();
            return insets;
        });

        buildChrome();
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startEngine();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildChrome();
        configureTransform();
        if (engine != null) {
            engine.setDisplayRotation(displayDegrees());
            engine.setPreviewRotation(imageRotation());
        }
    }

    // ------------------------------------------------------------------- chrome

    private void buildChrome() {
        if (topBar != null) root.removeView(topBar);
        if (bottomBar != null) root.removeView(bottomBar);
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        lensPill = new LinearLayout(this);
        lensPill.setOrientation(LinearLayout.HORIZONTAL);
        lensPill.setBackground(glass(dp(24), GLASS, HAIRLINE));
        lensPill.setPadding(dp(4), dp(4), dp(4), dp(4));
        segNormal = segment("Normal");
        segMacro = segment("Macro");
        lensPill.addView(segNormal, new LinearLayout.LayoutParams(dp(88), dp(46)));
        lensPill.addView(segMacro, new LinearLayout.LayoutParams(dp(88), dp(46)));
        segNormal.setOnClickListener(v -> pickLens(false));
        segMacro.setOnClickListener(v -> pickLens(true));

        zoomSlider = new ZoomSlider(this);
        zoomSlider.setListener(this::onZoomMark);

        statusPill = new TextView(this);
        statusPill.setMinHeight(dp(36));
        statusPill.setTextSize(13);
        statusPill.setTextColor(TEXT);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(dp(16), dp(9), dp(16), dp(9));
        statusPill.setBackground(glass(dp(18), GLASS_LIGHT, HAIRLINE));
        statusPill.setAlpha(0f);

        panel = buildPanel();
        panel.setVisibility(panelOpen ? View.VISIBLE : View.GONE);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(glass(dp(24), GLASS, HAIRLINE));
        thumb.setClipToOutline(true);
        thumb.setContentDescription("Last photo");
        thumb.setOnClickListener(v -> openLastPhoto());

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> {
            if (engine != null) engine.capture();
        });

        adjustButton = new TextView(this);
        adjustButton.setText(panelOpen ? "Done" : "Adjust");
        adjustButton.setTextSize(14);
        adjustButton.setTextColor(TEXT);
        adjustButton.setGravity(Gravity.CENTER);
        adjustButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        adjustButton.setBackground(glass(dp(21), GLASS, HAIRLINE));
        adjustButton.setOnClickListener(v -> togglePanel());

        if (landscape) {
            topBar = null;
            bottomBar = new LinearLayout(this);
            bottomBar.setOrientation(LinearLayout.VERTICAL);
            bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);

            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.bottomMargin = dp(10);
            bottomBar.addView(statusPill, statusParams);

            LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelParams.bottomMargin = dp(10);
            bottomBar.addView(panel, panelParams);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(thumb, box(dp(48), dp(48), dp(4), 0));
            row.addView(lensPill, marginWrap(dp(10), 0));
            row.addView(zoomSlider, marginWrap(dp(10), 0));
            View gap = new View(this);
            row.addView(gap, new LinearLayout.LayoutParams(0, dp(1), 1f));
            row.addView(shutter, box(dp(72), dp(72), 0, 0));
            row.addView(adjustButton, marginWrap(dp(12), 0));
            bottomBar.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(bottomBar, bottomParams());
            scrim.getLayoutParams().height = dp(220);
        } else {
            topBar = new LinearLayout(this);
            topBar.setGravity(Gravity.CENTER_HORIZONTAL);
            topBar.addView(lensPill);
            root.addView(topBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

            bottomBar = new LinearLayout(this);
            bottomBar.setOrientation(LinearLayout.VERTICAL);
            bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.bottomMargin = dp(14);
            bottomBar.addView(statusPill, statusParams);

            LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelParams.bottomMargin = dp(16);
            bottomBar.addView(panel, panelParams);

            LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            zoomParams.bottomMargin = dp(18);
            bottomBar.addView(zoomSlider, zoomParams);

            FrameLayout controls = new FrameLayout(this);
            FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(50), dp(50),
                    Gravity.START | Gravity.CENTER_VERTICAL);
            thumbParams.leftMargin = dp(4);
            controls.addView(thumb, thumbParams);
            FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER);
            controls.addView(shutter, shutterParams);
            FrameLayout.LayoutParams adjustParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL);
            adjustParams.rightMargin = dp(4);
            controls.addView(adjustButton, adjustParams);
            bottomBar.addView(controls, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
            root.addView(bottomBar, bottomParams());
            scrim.getLayoutParams().height = dp(320);
        }
        applyInsets();
        updateLensPill();
        updateZoomSlider();
        updateFocusPill();
        updateBrightnessLabel();
        restoreLastPhoto();
    }

    /**
     * The round thumbnail has to survive a restart, so when nothing was shot in
     * this session the newest image in DCIM/Camera is looked up in the media
     * store. Only rows LensLock inserted come back without a photo permission,
     * which keeps the app on the camera permission alone.
     */
    private void restoreLastPhoto() {
        if (lastPhoto == null) lastPhoto = newestCameraPhoto();
        if (lastPhoto != null) loadThumb(lastPhoto);
    }

    private Uri newestCameraPhoto() {
        // MediaStore stores the relative path with a trailing slash, the value
        // handed to insert may not carry it, so both shapes are matched.
        String folder = Environment.DIRECTORY_DCIM + "/Camera";
        String selection = MediaStore.Images.Media.RELATIVE_PATH + " IN (?, ?)";
        String[] args = {folder, folder + "/"};
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC, "
                + MediaStore.Images.Media._ID + " DESC";
        String[] projection = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, order)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception e) {
            Log.w("LensLock", "could not look up the last photo", e);
        }
        return null;
    }

    private LinearLayout buildPanel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(dp(20), GLASS_LIGHT, HAIRLINE));
        card.setPadding(dp(16), dp(12), dp(16), dp(6));

        LinearLayout focusRow = new LinearLayout(this);
        focusRow.setOrientation(LinearLayout.HORIZONTAL);
        focusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView focusLabel = new TextView(this);
        focusLabel.setText("Focus");
        focusLabel.setTextSize(14);
        focusLabel.setTextColor(TEXT);
        focusRow.addView(focusLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout focusSwitch = new LinearLayout(this);
        focusSwitch.setOrientation(LinearLayout.HORIZONTAL);
        focusSwitch.setBackground(glass(dp(19), 0x66000000, HAIRLINE));
        focusSwitch.setPadding(dp(3), dp(3), dp(3), dp(3));
        segAuto = segment("Auto");
        segManual = segment("Manual");
        segAuto.setTextSize(13);
        segManual.setTextSize(13);
        focusSwitch.addView(segAuto, new LinearLayout.LayoutParams(dp(68), dp(42)));
        focusSwitch.addView(segManual, new LinearLayout.LayoutParams(dp(68), dp(42)));
        focusRow.addView(focusSwitch);

        focusValue = new TextView(this);
        focusValue.setTextSize(13);
        focusValue.setTextColor(MUTED);
        focusValue.setGravity(Gravity.END);
        focusValue.setMinWidth(dp(56));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = dp(12);
        focusRow.addView(focusValue, valueParams);
        card.addView(focusRow);

        focusSlider = new SeekBar(this);
        focusSlider.setMax(1000);
        focusSlider.setVisibility(View.GONE);
        card.addView(focusSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout brightRow = new LinearLayout(this);
        brightRow.setOrientation(LinearLayout.HORIZONTAL);
        brightRow.setGravity(Gravity.CENTER_VERTICAL);
        brightRow.setPadding(0, dp(4), 0, 0);
        TextView brightLabel = new TextView(this);
        brightLabel.setText("Brightness");
        brightLabel.setTextSize(14);
        brightLabel.setTextColor(TEXT);
        brightRow.addView(brightLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brightnessValue = new TextView(this);
        brightnessValue.setTextSize(13);
        brightnessValue.setTextColor(MUTED);
        brightnessValue.setGravity(Gravity.END);
        brightRow.addView(brightnessValue);
        card.addView(brightRow);

        brightnessSlider = new SeekBar(this);
        card.addView(brightnessSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        styleSlider(focusSlider);
        styleSlider(brightnessSlider);

        segAuto.setOnClickListener(v -> setFocusMode(false));
        segManual.setOnClickListener(v -> setFocusMode(true));
        focusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) applyManualFocus(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        brightnessSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                evIndex = evMin + progress;
                if (engine != null) engine.setExposureIndex(evIndex);
                updateBrightnessLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        return card;
    }

    private void applyInsets() {
        int left = Math.max(lastInsets.left, dp(16));
        int right = Math.max(lastInsets.right, dp(16));
        if (topBar != null) {
            topBar.setPadding(left, lastInsets.top + dp(10), right, 0);
        }
        if (bottomBar != null) {
            bottomBar.setPadding(left, dp(6), right, lastInsets.bottom + dp(12));
        }
    }

    private FrameLayout.LayoutParams bottomParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    }

    private LinearLayout.LayoutParams box(int width, int height, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.leftMargin = left;
        p.rightMargin = right;
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    private LinearLayout.LayoutParams marginWrap(int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = left;
        p.rightMargin = right;
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    private void styleSlider(SeekBar bar) {
        int tint = 0xFFE8E8E8;
        bar.setProgressTintList(ColorStateList.valueOf(tint));
        bar.setThumbTintList(ColorStateList.valueOf(tint));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x3DFFFFFF));
        bar.setPadding(dp(4), 0, dp(4), 0);
    }

    private TextView segment(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(MUTED);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private GradientDrawable glass(float radius, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        d.setStroke(Math.max(1, Math.round(dp(0.8f))), stroke);
        return d;
    }

    private GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(19));
        d.setColor(fill);
        return d;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ camera

    private int displayDegrees() {
        android.view.Display display = getDisplay();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private int imageRotation() {
        return (sensorOrientation - displayDegrees() + 360) % 360;
    }

    private int previewRotation() {
        return ((imageRotation() + PREVIEW_ROTATION_OFFSET) % 360 + 360) % 360;
    }

    private void startEngine() {
        if (engine != null) return;
        engine = new CameraEngine(this, this);
        engine.setDisplayRotation(displayDegrees());
        engine.setPreviewRotation(imageRotation());
    }

    private void attachPreview() {
        if (engine == null || surfaceTexture == null || previewSize == null) return;
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        engine.setPreviewSurface(new Surface(surfaceTexture));
    }

    /**
     * Dimensions of the image as the surface texture presents it. The camera HAL
     * may rotate the preview stream, so the requested size is not the aspect the
     * screen actually receives.
     */
    private float[] presentedDims() {
        float width = previewSize.getWidth();
        float height = previewSize.getHeight();
        if (surfaceTexture != null) {
            float[] values = new float[16];
            surfaceTexture.getTransformMatrix(values);
            // column major 4x4, so the 2x2 is m[0] m[4] / m[1] m[5]
            boolean quarterTurn = Math.abs(values[1]) > 0.5f || Math.abs(values[4]) > 0.5f;
            if (!loggedMatrix) {
                loggedMatrix = true;
                Log.i("LensLock", "surface transform " + values[0] + " " + values[1] + " "
                        + values[4] + " " + values[5] + " quarterTurn=" + quarterTurn);
            }
            if (quarterTurn) {
                return new float[]{height, width};
            }
        }
        return new float[]{width, height};
    }

    private void configureTransform() {
        if (preview == null || previewSize == null) return;
        int viewWidth = preview.getWidth();
        int viewHeight = preview.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        float[] dims = presentedDims();
        float presentedWidth = dims[0];
        float presentedHeight = dims[1];

        int rotation = previewRotation();
        boolean swapped = rotation == 90 || rotation == 270;
        float rotatedWidth = swapped ? presentedHeight : presentedWidth;
        float rotatedHeight = swapped ? presentedWidth : presentedHeight;

        float cx = viewWidth / 2f;
        float cy = viewHeight / 2f;
        Matrix matrix = new Matrix();
        // the texture is stretched to the view bounds, undo that with the presented aspect
        matrix.postScale(presentedWidth / viewWidth, presentedHeight / viewHeight, cx, cy);
        matrix.postRotate(rotation, cx, cy);
        // a single scale for both axes, so the picture cannot stretch
        float cover = Math.max(viewWidth / rotatedWidth, viewHeight / rotatedHeight) * zoom;
        matrix.postScale(cover, cover, cx, cy);
        preview.setTransform(matrix);
    }

    @Override
    public void onReady(List<CameraEngine.Lens> lenses, CameraEngine.Lens selected, android.util.Size size,
                        float maxFocus, float step, int min, int max, int orientation) {
        previewSize = size;
        selectedLens = selected;
        maxFocusDiopters = maxFocus > 0 ? maxFocus : 10f;
        evStep = step;
        evMin = min;
        evMax = max;
        sensorOrientation = orientation;

        boolean hasMacro = false;
        for (CameraEngine.Lens l : lenses) if (l.ultrawide) hasMacro = true;
        segMacro.setAlpha(hasMacro ? 1f : 0.35f);
        segMacro.setEnabled(hasMacro);

        brightnessSlider.setMax(Math.max(1, evMax - evMin));
        brightnessSlider.setProgress(Math.min(Math.max(evIndex - evMin, 0), evMax - evMin));
        updateBrightnessLabel();
        updateLensPill();
        updateZoomSlider();
        updateFocusPill();
        attachPreview();
        configureTransform();
    }

    @Override
    public void onSessionReady(CameraEngine.Lens lens, boolean previewPinned) {
        selectedLens = lens;
        updateLensPill();
        if (pendingEquivalentZoom > 0f) {
            // keep the framing the user had when switching sensor
            zoom = clampCrop(pendingEquivalentZoom / baseZoom());
            pendingEquivalentZoom = -1f;
        }
        engine.setPreviewRotation(imageRotation());
        engine.setZoom(zoom);
        updateZoomSlider();
        configureTransform();
        engine.setExposureIndex(evIndex);
        engine.setManualFocus(manualFocus, dioptersFor(focusSlider.getProgress()));
    }

    @Override
    public void onSaved(Uri uri, String detail) {
        lastPhoto = uri;
        loadThumb(uri);
        showStatus("Saved to Camera", false);
    }

    @Override
    public void onError(String message) {
        showStatus(message, true);
    }

    @Override
    public void onStatus(String text) {
        // engine meta text is for logcat only
    }

    // ----------------------------------------------------------------- actions

    private void pickLens(boolean macro) {
        if (engine == null) return;
        for (CameraEngine.Lens l : engine.lenses()) {
            if (l.ultrawide == macro) {
                pendingEquivalentZoom = zoom * baseZoom();
                engine.setLens(l);
                selectedLens = l;
                updateLensPill();
                updateZoomSlider();
                return;
            }
        }
    }

    private CameraEngine.Lens lensFor(boolean macro) {
        if (engine == null) return null;
        for (CameraEngine.Lens l : engine.lenses()) {
            if (l.ultrawide == macro) return l;
        }
        return null;
    }

    private float baseZoom() {
        return (selectedLens != null && selectedLens.ultrawide) ? ULTRAWIDE_BASE_ZOOM : 1f;
    }

    private float clampCrop(float crop) {
        return Math.max(1f, Math.min(MAX_CROP, crop));
    }

    /** A preset was tapped: marks are field of view, not crop factor. */
    private void onZoomMark(float mark) {
        applyZoom(mark / baseZoom());
    }

    private void applyZoom(float crop) {
        float next = clampCrop(crop);
        if (Math.abs(next - zoom) < 0.001f) return;
        zoom = next;
        if (engine != null) engine.setZoom(zoom);
        configureTransform();
        updateZoomSlider();
    }

    private void updateLensPill() {
        if (selectedLens == null || segNormal == null) return;
        styleSegment(segNormal, !selectedLens.ultrawide);
        styleSegment(segMacro, selectedLens.ultrawide);
    }

    private void updateZoomSlider() {
        if (zoomSlider == null) return;
        zoomSlider.setMarks(marksForLens());
        zoomSlider.setZoom(zoom * baseZoom());
    }

    /** Only the pinned sensor's range is offered, so nothing can switch lenses here. */
    private float[] marksForLens() {
        if (baseZoom() < 1f) {
            return new float[]{0.5f, 1f, 2f};
        }
        return new float[]{1f, 2f, 3f, 4f};
    }

    private void updateFocusPill() {
        if (segAuto == null) return;
        styleSegment(segAuto, !manualFocus);
        styleSegment(segManual, manualFocus);
        focusSlider.setVisibility(manualFocus ? View.VISIBLE : View.GONE);
        focusValue.setText(manualFocus ? distanceLabel(focusSlider.getProgress()) : "Auto");
    }

    private void styleSegment(TextView view, boolean active) {
        view.setTextColor(active ? 0xFF111111 : MUTED);
        view.setBackground(active ? pill(0xFFF2F2F2) : null);
    }

    private void togglePanel() {
        panelOpen = !panelOpen;
        if (panelOpen) {
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(0f);
            panel.setTranslationY(dp(18));
            panel.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            panel.animate().alpha(0f).translationY(dp(14)).setDuration(140)
                    .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        }
        adjustButton.setText(panelOpen ? "Done" : "Adjust");
    }

    private void setFocusMode(boolean manual) {
        manualFocus = manual;
        updateFocusPill();
        if (engine != null) engine.setManualFocus(manual, dioptersFor(focusSlider.getProgress()));
    }

    private void applyManualFocus(int progress) {
        focusValue.setText(distanceLabel(progress));
        if (engine != null) engine.setManualFocus(true, dioptersFor(progress));
    }

    private float dioptersFor(int progress) {
        return maxFocusDiopters * (progress / 1000f);
    }

    private String distanceLabel(int progress) {
        float d = dioptersFor(progress);
        if (d < 0.08f) return "Far";
        float cm = 100f / d;
        if (cm >= 100f) return String.format(Locale.US, "%.1f m", cm / 100f);
        return String.format(Locale.US, "%.0f cm", cm);
    }

    private void updateBrightnessLabel() {
        if (brightnessValue == null) return;
        float value = evIndex * evStep;
        String text = String.format(Locale.US, "%+.1f", value);
        brightnessValue.setText(text.equals("+0.0") ? "0" : text);
    }

    private void showStatus(String message, boolean error) {
        statusPill.setText(message);
        statusPill.setTextColor(error ? ERROR : TEXT);
        statusPill.setVisibility(View.VISIBLE);
        statusPill.animate().cancel();
        statusPill.animate().alpha(1f).setDuration(140).start();
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(() -> statusPill.animate().alpha(0f).setDuration(220).start(), 1800);
    }

    /** Pinch feedback, updated on every gesture frame. */
    private void showZoomReadout() {
        statusPill.setText(String.format(Locale.US, "%.1fx", zoom * baseZoom()));
        statusPill.setTextColor(TEXT);
        statusPill.setVisibility(View.VISIBLE);
        statusPill.animate().cancel();
        statusPill.setAlpha(1f);
    }

    private void loadThumb(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 8;
            try (InputStream in = resolver.openInputStream(uri)) {
                Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                if (bmp != null) thumb.setImageBitmap(bmp);
            }
        } catch (Exception ignored) {
        }
    }

    private void openLastPhoto() {
        if (lastPhoto == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(lastPhoto, "image/jpeg");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // -------------------------------------------------------- hardware shutter

    /**
     * Volume up is the hardware shutter, the way the stock camera does it. Both
     * directions of the key are consumed so a press does not also change the
     * media volume or play the volume tone, and a held key fires once.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            shutterKeyCapture();
        }
        return true;
    }

    /**
     * The key press leaves no finger on the shutter, so the button animates to
     * show the shot went off. The release runs on the view's own handler, because
     * the status pill clears every callback on {@code ui} and would strand the
     * button in the pressed state.
     */
    private void shutterKeyCapture() {
        if (engine == null) return;
        shutter.setPressed(true);
        shutter.postDelayed(() -> shutter.setPressed(false), 120);
        engine.capture();
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startEngine();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startEngine();
        } else {
            showStatus("Camera permission is off", true);
        }
    }
}
