package com.simon.lenslock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Square focus box drawn where the user tapped, fading out on its own.
 */
public class FocusRing extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float cx;
    private float cy;
    private float alpha;
    private ValueAnimator animator;

    public FocusRing(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(0xFFFFC24B);
        setClickable(false);
        setFocusable(false);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void show(float x, float y) {
        cx = x;
        cy = y;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(900);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            alpha = t < 0.55f ? 1f : 1f - (t - 0.55f) / 0.45f;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (alpha <= 0f) return;
        float half = dp(44f);
        box.set(cx - half, cy - half, cx + half, cy + half);
        paint.setAlpha((int) (alpha * 255));
        canvas.drawRoundRect(box, dp(6f), dp(6f), paint);
    }
}
