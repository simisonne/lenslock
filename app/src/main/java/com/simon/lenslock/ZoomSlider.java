package com.simon.lenslock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Zoom markers with an indicator that slides between them. The marks are field of
 * view values, the indicator position is interpolated from the live zoom, so it
 * never jumps between slots while pinching.
 */
public class ZoomSlider extends View {

    public interface Listener {
        void onMarkTapped(float mark);
    }

    private static final int GLASS = 0xCC141414;
    private static final int HAIRLINE = 0x26FFFFFF;
    private static final int MUTED = 0x99FFFFFF;
    private static final int ACTIVE_TEXT = 0xFF111111;

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float[] marks = {1f, 2f, 3f, 4f};
    private float zoom = 1f;
    private float slotWidth;
    private float padding;
    private Listener listener;

    public ZoomSlider(Context context) {
        this(context, null);
    }

    public ZoomSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        background.setColor(GLASS);
        hairline.setStyle(Paint.Style.STROKE);
        hairline.setStrokeWidth(dp(0.8f));
        hairline.setColor(HAIRLINE);
        indicator.setColor(0xFFF2F2F2);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f,
                getResources().getDisplayMetrics()));
        label.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMarks(float[] marks) {
        if (marks == null || marks.length == 0) return;
        this.marks = marks;
        requestLayout();
        updateAccessibility();
        invalidate();
    }

    public void setZoom(float fieldOfView) {
        if (Math.abs(fieldOfView - zoom) < 0.001f) return;
        zoom = fieldOfView;
        updateAccessibility();
        invalidate();
    }

    private void updateAccessibility() {
        setContentDescription(String.format(java.util.Locale.US, "Zoom %.1fx", zoom));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        slotWidth = dp(52);
        padding = dp(3);
        int width = Math.round(marks.length * slotWidth + padding * 2);
        int height = Math.round(dp(42) + padding * 2);
        setMeasuredDimension(width, height);
    }

    private float slotCenter(int index) {
        return padding + slotWidth * (index + 0.5f);
    }

    private float indicatorCenterX() {
        if (marks.length == 0) return padding;
        if (zoom <= marks[0]) return slotCenter(0);
        if (zoom >= marks[marks.length - 1]) return slotCenter(marks.length - 1);
        for (int i = 0; i < marks.length - 1; i++) {
            if (zoom >= marks[i] && zoom <= marks[i + 1]) {
                float span = marks[i + 1] - marks[i];
                float t = span <= 0f ? 0f : (zoom - marks[i]) / span;
                return slotCenter(i) + t * (slotCenter(i + 1) - slotCenter(i));
            }
        }
        return slotCenter(0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, radius, radius, background);
        canvas.drawRoundRect(rect, radius, radius, hairline);

        float centerX = indicatorCenterX();
        float half = (slotWidth - dp(7)) / 2f;
        rect.set(centerX - half, padding + dp(1.5f), centerX + half, getHeight() - padding - dp(1.5f));
        float pillRadius = rect.height() / 2f;
        canvas.drawRoundRect(rect, pillRadius, pillRadius, indicator);

        float baseline = getHeight() / 2f - (label.descent() + label.ascent()) / 2f;
        for (int i = 0; i < marks.length; i++) {
            float x = slotCenter(i);
            float distance = Math.abs(centerX - x);
            float covered = Math.max(0f, 1f - distance / (slotWidth * 0.55f));
            label.setColor(blend(MUTED, ACTIVE_TEXT, covered));
            canvas.drawText(text(marks[i]), x, baseline, label);
        }
    }

    private String text(float mark) {
        return mark < 1f ? String.format(java.util.Locale.US, "%.1fx", mark)
                : String.format(java.util.Locale.US, "%.0fx", mark);
    }

    private static int blend(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * clamped);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * clamped);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * clamped);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP:
                if (listener != null) {
                    int nearest = 0;
                    float best = Float.MAX_VALUE;
                    for (int i = 0; i < marks.length; i++) {
                        float delta = Math.abs(event.getX() - slotCenter(i));
                        if (delta < best) {
                            best = delta;
                            nearest = i;
                        }
                    }
                    listener.onMarkTapped(marks[nearest]);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
