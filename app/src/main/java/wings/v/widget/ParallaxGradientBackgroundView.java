package wings.v.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import wings.v.R;

@SuppressWarnings("PMD.NullAssignment")
public class ParallaxGradientBackgroundView extends AppCompatImageView {

    private static final long DRIFT_DURATION_MS = 26_000L;
    private static final float BASE_SCALE = 1.18f;

    private float driftProgress;
    private float pagerProgress;

    @Nullable
    private ValueAnimator driftAnimator;

    public ParallaxGradientBackgroundView(Context context) {
        super(context);
        init();
    }

    public ParallaxGradientBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ParallaxGradientBackgroundView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void setPagerProgress(float value) {
        if (pagerProgress == value) {
            return;
        }
        pagerProgress = value;
        applyTransform();
    }

    private void init() {
        setImageResource(R.drawable.suw_intro_bg);
        setScaleType(ScaleType.CENTER_CROP);
        setScaleX(BASE_SCALE);
        setScaleY(BASE_SCALE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (driftAnimator != null) {
            driftAnimator.cancel();
        }
        driftAnimator = ValueAnimator.ofFloat(0f, 1f);
        driftAnimator.setDuration(DRIFT_DURATION_MS);
        driftAnimator.setRepeatCount(ValueAnimator.INFINITE);
        driftAnimator.setInterpolator(new LinearInterpolator());
        driftAnimator.addUpdateListener(animation -> {
            driftProgress = (float) animation.getAnimatedValue();
            applyTransform();
        });
        driftAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (driftAnimator != null) {
            driftAnimator.cancel();
            driftAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    private void applyTransform() {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }

        float phase = (float) (Math.PI * 2d * driftProgress);
        float parallax = pagerProgress - 1f;
        float driftX = 0.014f * width * (float) Math.sin(phase);
        float driftY = 0.012f * height * (float) Math.cos(phase * 0.85f);
        float maxX = width * (BASE_SCALE - 1f) * 0.42f;
        float maxY = height * (BASE_SCALE - 1f) * 0.42f;
        setTranslationX(clamp(driftX - 0.02f * width * parallax, -maxX, maxX));
        setTranslationY(clamp(driftY + 0.018f * height * parallax, -maxY, maxY));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
