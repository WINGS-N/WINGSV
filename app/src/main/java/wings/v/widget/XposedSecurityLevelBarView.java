package wings.v.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import wings.v.R;
import wings.v.core.XposedSecurityScore;

public class XposedSecurityLevelBarView extends View {

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clipPath = new Path();
    private XposedSecurityScore.Level level = XposedSecurityScore.Level.WEAK;
    private int fillWidth;
    private int gradientStartColor;
    private int gradientEndColor;

    public XposedSecurityLevelBarView(Context context) {
        this(context, null);
    }

    public XposedSecurityLevelBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        backgroundPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_surface_alt));
        gradientStartColor = ContextCompat.getColor(context, R.color.wingsv_accent);
        gradientEndColor = ContextCompat.getColor(context, R.color.wingsv_success);
        updateFillColor();
    }

    public void setState(int progress, XposedSecurityScore.Level level) {
        this.level = level == null ? XposedSecurityScore.Level.WEAK : level;
        this.fillWidth = resolveFillWidth(this.level);
        updateFillColor();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (getResources().getDisplayMetrics().density * 16f);
        int resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolvedHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        rect.set(0f, 0f, getWidth(), getHeight());
        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint);
        if (fillWidth > 0) {
            rect.set(0f, 0f, fillWidth, getHeight());
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            gradientPaint.setShader(
                new LinearGradient(
                    0f,
                    0f,
                    Math.max(fillWidth, 1),
                    getHeight(),
                    gradientStartColor,
                    gradientEndColor,
                    Shader.TileMode.CLAMP
                )
            );
            canvas.drawRoundRect(rect, radius, radius, gradientPaint);
        }
        canvas.restore();
    }

    private void updateFillColor() {
        int colorRes;
        switch (level) {
            case MAXIMUM:
                colorRes = R.color.wingsv_success;
                break;
            case MEDIUM:
                colorRes = R.color.wingsv_accent;
                break;
            case WEAK:
            default:
                colorRes = R.color.wingsv_warning;
                break;
        }
        fillPaint.setColor(ContextCompat.getColor(getContext(), colorRes));
    }

    private int resolveFillWidth(XposedSecurityScore.Level level) {
        float width;
        switch (level) {
            case MAXIMUM:
                width = getWidth();
                break;
            case MEDIUM:
                width = (getWidth() * 2f) / 3f;
                break;
            case WEAK:
            default:
                width = getWidth() / 3f;
                break;
        }
        return Math.round(width);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fillWidth = resolveFillWidth(level);
    }
}
