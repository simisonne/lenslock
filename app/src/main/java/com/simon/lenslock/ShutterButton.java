package com.simon.lenslock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Camera style shutter: hairline ring, solid core, core shrinks while pressed.
 */
public class ShutterButton extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float coreScale = 1f;
    private ValueAnimator animator;

    public ShutterButton(Context context) {
        this(context, null);
    }

    public ShutterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3f));
        ringPaint.setColor(0xE6FFFFFF);
        corePaint.setStyle(Paint.Style.FILL);
        corePaint.setColor(0xFFFFFFFF);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Take photo");
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(2f);
        if (!isEnabled()) {
            ringPaint.setAlpha(0x59);
            corePaint.setAlpha(0x59);
        } else {
            ringPaint.setAlpha(0xE6);
            corePaint.setAlpha(0xFF);
        }
        canvas.drawCircle(cx, cy, radius - dp(1.75f), ringPaint);
        canvas.drawCircle(cx, cy, radius * 0.76f * coreScale, corePaint);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        animateCore(pressed ? 0.84f : 1f);
    }

    private void animateCore(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(coreScale, target);
        animator.setDuration(pressed(getContext()) ? 90 : 160);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            coreScale = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private boolean pressed(Context context) {
        return isPressed();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidate();
    }
}
