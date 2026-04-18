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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import wings.v.R;
import wings.v.core.XposedAttackStatsStore;

public class XposedWeeklyStatsGraphView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path barPath = new Path();
    private List<XposedAttackStatsStore.DailyPoint> points = new ArrayList<>();
    private int maxCount;
    private int gradientStartColor;
    private int gradientEndColor;

    public XposedWeeklyStatsGraphView(Context context) {
        this(context, null);
    }

    public XposedWeeklyStatsGraphView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        barPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_surface_alt));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_text_secondary));
        labelPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 11f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        selectedLabelPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_window));
        selectedLabelPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 11f);
        selectedLabelPaint.setTextAlign(Paint.Align.CENTER);
        selectedLabelPaint.setFakeBoldText(true);
        selectedLabelBgPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_accent));
        outlinePaint.setColor(ContextCompat.getColor(context, R.color.wingsv_surface_alt));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        gradientStartColor = ContextCompat.getColor(context, R.color.wingsv_accent);
        gradientEndColor = ContextCompat.getColor(context, R.color.wingsv_success);
    }

    public void setPoints(@NonNull List<XposedAttackStatsStore.DailyPoint> points) {
        this.points = new ArrayList<>(points);
        this.maxCount = 0;
        for (XposedAttackStatsStore.DailyPoint point : points) {
            maxCount = Math.max(maxCount, point.count);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (getResources().getDisplayMetrics().density * 164f);
        int resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolvedHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float chartTop = density * 6f;
        float chartBottom = getHeight() - density * 34f;
        float chartHeight = Math.max(density * 24f, chartBottom - chartTop);
        float slotWidth = getWidth() / (float) points.size();
        float barWidth = Math.min(slotWidth * 0.42f, density * 22f);
        float radius = barWidth / 2f;
        for (int index = 0; index < points.size(); index++) {
            XposedAttackStatsStore.DailyPoint point = points.get(index);
            float centerX = slotWidth * index + slotWidth / 2f;
            float normalized = maxCount <= 0 ? 0f : point.count / (float) maxCount;
            float barHeight = Math.max(point.count > 0 ? density * 8f : 0f, chartHeight * normalized);
            rect.set(centerX - barWidth / 2f, chartBottom - barHeight, centerX + barWidth / 2f, chartBottom);
            barPath.reset();
            barPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(barPath);
            canvas.drawRoundRect(rect, radius, radius, barPaint);
            gradientPaint.setShader(
                new LinearGradient(
                    0f,
                    rect.bottom,
                    0f,
                    (rect.top + rect.bottom) * 0.5f,
                    gradientStartColor,
                    gradientEndColor,
                    Shader.TileMode.CLAMP
                )
            );
            canvas.drawRoundRect(rect, radius, radius, gradientPaint);
            canvas.restore();
            canvas.drawRoundRect(rect, radius, radius, outlinePaint);
            float labelY = getHeight() - density * 10f;
            if (index == points.size() - 1) {
                canvas.drawCircle(centerX, labelY - density * 4f, density * 13f, selectedLabelBgPaint);
                canvas.drawText(point.getWeekLabel(), centerX, labelY, selectedLabelPaint);
            } else {
                canvas.drawText(point.getWeekLabel(), centerX, labelY, labelPaint);
            }
        }
    }
}
