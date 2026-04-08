package wings.v.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
public class ConnectionChoiceAnimationView extends View {

    private static final long LOOP_DURATION_MS = 2_800L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;

    @Nullable
    private ValueAnimator animator;

    public ConnectionChoiceAnimationView(Context context) {
        super(context);
    }

    public ConnectionChoiceAnimationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConnectionChoiceAnimationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_DURATION_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }

        float phase = (float) (Math.PI * 2d * progress);
        float centerY = height * 0.52f;
        drawPhone(canvas, width * 0.28f, centerY, Math.min(width, height), phase);
        drawDots(canvas, width, centerY, phase);
        drawQuestionMark(canvas, width * 0.76f, centerY, Math.min(width, height), phase);
    }

    private void drawPhone(Canvas canvas, float centerX, float centerY, float size, float phase) {
        float phoneWidth = size * 0.24f;
        float phoneHeight = size * 0.42f;
        float bob = size * 0.018f * (float) Math.sin(phase);
        rect.set(
            centerX - phoneWidth * 0.5f,
            centerY - phoneHeight * 0.5f + bob,
            centerX + phoneWidth * 0.5f,
            centerY + phoneHeight * 0.5f + bob
        );
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2EFFFFFF);
        canvas.drawRoundRect(rect, phoneWidth * 0.18f, phoneWidth * 0.18f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.012f);
        paint.setColor(0xE8FFFFFF);
        canvas.drawRoundRect(rect, phoneWidth * 0.18f, phoneWidth * 0.18f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(
            centerX - phoneWidth * 0.16f,
            rect.top + phoneHeight * 0.08f,
            centerX + phoneWidth * 0.16f,
            rect.top + phoneHeight * 0.1f,
            size * 0.01f,
            size * 0.01f,
            paint
        );
        canvas.drawCircle(centerX, rect.bottom - phoneHeight * 0.08f, size * 0.012f, paint);
    }

    private void drawDots(Canvas canvas, float width, float centerY, float phase) {
        float startX = width * 0.43f;
        float gap = width * 0.08f;
        for (int i = 0; i < 3; i++) {
            float local = 0.5f + 0.5f * (float) Math.sin(phase - i * 0.8f);
            float radius = width * (0.018f + 0.008f * local);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (96 + 130 * local), 255, 255, 255));
            canvas.drawCircle(startX + gap * i, centerY, radius, paint);
        }
    }

    private void drawQuestionMark(Canvas canvas, float centerX, float centerY, float size, float phase) {
        float bob = size * 0.016f * (float) Math.cos(phase + 0.6f);
        float radius = size * 0.15f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2EFFFFFF);
        canvas.drawCircle(centerX, centerY + bob, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.012f);
        paint.setColor(0xDFFFFFFF);
        canvas.drawCircle(centerX, centerY + bob, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(size * 0.2f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = centerY + bob - (metrics.ascent + metrics.descent) * 0.5f;
        canvas.drawText("?", centerX, baseline, paint);
    }
}
