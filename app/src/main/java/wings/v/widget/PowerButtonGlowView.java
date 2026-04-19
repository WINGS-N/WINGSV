package wings.v.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import wings.v.R;
import wings.v.core.DisplayDensityUtils;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.ShortVariable",
        "PMD.ShortMethodName",
        "PMD.OnlyOneReturn",
        "PMD.TooManyMethods",
        "PMD.UselessParentheses",
        "PMD.AvoidLiteralsInIfCondition",
    }
)
public class PowerButtonGlowView extends View {

    private static final long ANIMATION_DURATION_MS = 5600L;
    private static final float MAX_SPEED_BYTES_PER_SECOND = 8f * 1024f * 1024f;
    private static final float TWO_PI = (float) (Math.PI * 2d);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int glowColor;
    private ValueAnimator animator;
    private float phase;
    private float displayedIntensity;
    private float targetIntensity;
    private boolean connected;

    public PowerButtonGlowView(Context context) {
        this(context, null);
    }

    public PowerButtonGlowView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PowerButtonGlowView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        glowColor = resolveGlowColor(context);
        setWillNotDraw(false);
    }

    public void setTrafficState(boolean connected, long bytesPerSecond) {
        float speed = Math.max(0L, bytesPerSecond);
        float speedFactor = speed <= 0f ? 0f : (float) Math.sqrt(Math.min(1f, speed / MAX_SPEED_BYTES_PER_SECOND));
        this.connected = connected;
        this.targetIntensity = connected ? 0.22f + 0.78f * speedFactor : 0f;
        setAlpha(connected ? 1f : 0f);
        if (connected) {
            startAnimationIfNeeded();
        } else {
            displayedIntensity = 0f;
            stopAnimationIfNeeded();
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (connected) {
            startAnimationIfNeeded();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimationIfNeeded();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!connected || displayedIntensity <= 0.01f) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float min = Math.min(width, height);
        float intensity = displayedIntensity;
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float buttonRadius = Math.min(dp(90f), min * 0.42f);
        float haloRadius = buttonRadius + dp(2f + 5f * intensity);
        float orbitRadius = buttonRadius + dp(2f + 4f * intensity);

        drawOuterRing(canvas, cx, cy, haloRadius, intensity);

        drawOrbitBlob(canvas, cx, cy, orbitRadius, phase, 0.94f, dp(16f + 14f * intensity), (int) (78f * intensity));
        drawOrbitBlob(
            canvas,
            cx,
            cy,
            orbitRadius * 0.99f,
            phase + 0.37f,
            0.92f,
            dp(14f + 10f * intensity),
            (int) (54f * intensity)
        );
        drawOrbitBlob(
            canvas,
            cx,
            cy,
            orbitRadius * 0.98f,
            phase + 0.68f,
            0.96f,
            dp(11f + 8f * intensity),
            (int) (38f * intensity)
        );

        drawPulseRing(canvas, cx, cy, haloRadius + dp(1f + 4f * pulse()), (int) (70f * intensity));
    }

    private void drawOrbitBlob(
        Canvas canvas,
        float cx,
        float cy,
        float orbitRadius,
        float orbitPhase,
        float verticalScale,
        float radius,
        int alpha
    ) {
        float angle = orbitPhase * TWO_PI;
        float x = cx + (float) Math.cos(angle) * orbitRadius;
        float y = cy + (float) Math.sin(angle) * orbitRadius * verticalScale;
        drawSoftCircle(canvas, x, y, radius, alpha);
    }

    private void drawSoftCircle(Canvas canvas, float cx, float cy, float radius, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 5; i >= 1; i--) {
            float layer = i / 5f;
            paint.setColor(withAlpha(glowColor, (int) ((alpha * (1f - layer * 0.12f)) / i)));
            canvas.drawCircle(cx, cy, radius * layer, paint);
        }
    }

    private void drawOuterRing(Canvas canvas, float cx, float cy, float radius, float intensity) {
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 4; i >= 1; i--) {
            float layer = i / 4f;
            paint.setStrokeWidth(dp(7f + 10f * layer));
            paint.setColor(withAlpha(glowColor, (int) ((36f * intensity) / i)));
            canvas.drawCircle(cx, cy, radius + dp(1f * layer), paint);
        }
    }

    private void drawPulseRing(Canvas canvas, float cx, float cy, float radius, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f + 3f * displayedIntensity));
        paint.setColor(withAlpha(glowColor, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private float pulse() {
        return 0.5f + 0.5f * (float) Math.sin(phase * TWO_PI);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return DisplayDensityUtils.dpToPx(getContext(), Math.round(value));
    }

    private static int resolveGlowColor(Context context) {
        TypedValue typedValue = new TypedValue();
        if (
            context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true) &&
            typedValue.resourceId != 0
        ) {
            return ContextCompat.getColor(context, typedValue.resourceId);
        }
        if (
            context.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true) &&
            typedValue.data != 0
        ) {
            return typedValue.data;
        }
        if (
            context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true) &&
            typedValue.resourceId != 0
        ) {
            return ContextCompat.getColor(context, typedValue.resourceId);
        }
        if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true) && typedValue.data != 0) {
            return typedValue.data;
        }
        return ContextCompat.getColor(context, R.color.wingsv_power_on);
    }

    private void startAnimationIfNeeded() {
        if (animator != null && animator.isStarted()) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIMATION_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            displayedIntensity += (targetIntensity - displayedIntensity) * 0.075f;
            if (Math.abs(targetIntensity - displayedIntensity) < 0.005f) {
                displayedIntensity = targetIntensity;
            }
            invalidate();
        });
        animator.start();
    }

    private void stopAnimationIfNeeded() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
