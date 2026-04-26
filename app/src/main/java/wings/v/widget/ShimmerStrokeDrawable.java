package wings.v.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public final class ShimmerStrokeDrawable extends Drawable {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private final int[] gradientColors;
    private final float[] gradientStops = { 0f, 0.35f, 0.5f, 0.65f, 1f };
    private final float strokeWidthPx;
    private final float cornerRadiusPx;
    private final long cycleDurationMs;

    @Nullable
    private LinearGradient gradient;

    private long startTimeMs = SystemClock.uptimeMillis();
    private boolean running = true;

    public ShimmerStrokeDrawable(
        @ColorInt int baseColor,
        @ColorInt int accentA,
        @ColorInt int accentB,
        @ColorInt int fillColor,
        float strokeWidthPx,
        float cornerRadiusPx,
        long cycleDurationMs
    ) {
        this.strokeWidthPx = strokeWidthPx;
        this.cornerRadiusPx = cornerRadiusPx;
        this.cycleDurationMs = cycleDurationMs;
        this.gradientColors = new int[] { baseColor, accentA, baseColor, accentB, baseColor };
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidthPx);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
    }

    public void setRunning(boolean running) {
        if (this.running == running) {
            return;
        }
        this.running = running;
        if (running) {
            startTimeMs = SystemClock.uptimeMillis();
            invalidateSelf();
        }
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        gradient = null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        float inset = strokeWidthPx / 2f;
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint);

        if (gradient == null) {
            gradient = new LinearGradient(
                0f,
                0f,
                Math.max(1f, bounds.width()),
                0f,
                gradientColors,
                gradientStops,
                Shader.TileMode.MIRROR
            );
            strokePaint.setShader(gradient);
        }

        long elapsed = SystemClock.uptimeMillis() - startTimeMs;
        float progress = (elapsed % cycleDurationMs) / (float) cycleDurationMs;
        float width = bounds.width();
        float translate = (progress * 2f - 1f) * width;
        shaderMatrix.reset();
        shaderMatrix.setTranslate(translate, 0f);
        gradient.setLocalMatrix(shaderMatrix);

        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, strokePaint);

        if (running) {
            invalidateSelf();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        strokePaint.setAlpha(alpha);
        fillPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        strokePaint.setColorFilter(colorFilter);
        fillPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @ColorInt
    public static int withAlpha(@ColorInt int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @ColorInt
    public static int blend(@ColorInt int color, @ColorInt int towards, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int rA = Color.red(color);
        int gA = Color.green(color);
        int bA = Color.blue(color);
        int rB = Color.red(towards);
        int gB = Color.green(towards);
        int bB = Color.blue(towards);
        int r = Math.round(rA + (rB - rA) * t);
        int g = Math.round(gA + (gB - gA) * t);
        int b = Math.round(bA + (bB - bA) * t);
        return Color.argb(Color.alpha(color), r, g, b);
    }
}
