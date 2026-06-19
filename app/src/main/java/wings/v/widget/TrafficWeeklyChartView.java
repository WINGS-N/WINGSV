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
import wings.v.core.SharingTrafficStatsStore;

public class TrafficWeeklyChartView extends View {

    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recvPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path barPath = new Path();
    private List<SharingTrafficStatsStore.DailyTraffic> points = new ArrayList<>();
    private long maxBytes;
    private int sentStartColor;
    private int sentEndColor;
    private int recvStartColor;
    private int recvEndColor;

    public TrafficWeeklyChartView(Context context) {
        this(context, null);
    }

    public TrafficWeeklyChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        slotPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_surface_alt));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.wingsv_text_secondary));
        labelPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 11f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        sentStartColor = ContextCompat.getColor(context, R.color.wingsv_accent);
        sentEndColor = ContextCompat.getColor(context, R.color.wingsv_success);
        recvStartColor = ContextCompat.getColor(context, R.color.wingsv_text_secondary);
        recvEndColor = ContextCompat.getColor(context, R.color.wingsv_surface_alt);
    }

    public void setPoints(@NonNull List<SharingTrafficStatsStore.DailyTraffic> points, long maxBytes) {
        this.points = new ArrayList<>(points);
        this.maxBytes = Math.max(1L, maxBytes);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (getResources().getDisplayMetrics().density * 180f);
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
        float pairGap = density * 3f;
        float barWidth = Math.min((slotWidth - pairGap) * 0.36f, density * 12f);
        float radius = barWidth / 2f;
        for (int index = 0; index < points.size(); index++) {
            SharingTrafficStatsStore.DailyTraffic point = points.get(index);
            float centerX = slotWidth * index + slotWidth / 2f;
            float sentLeft = centerX - pairGap / 2f - barWidth;
            float recvLeft = centerX + pairGap / 2f;
            drawBar(
                canvas,
                sentLeft,
                sentLeft + barWidth,
                chartBottom,
                chartHeight,
                radius,
                point.sentBytes,
                sentStartColor,
                sentEndColor,
                density
            );
            drawBar(
                canvas,
                recvLeft,
                recvLeft + barWidth,
                chartBottom,
                chartHeight,
                radius,
                point.receivedBytes,
                recvStartColor,
                recvEndColor,
                density
            );
            canvas.drawText(point.getWeekLabel(), centerX, chartBottom + density * 18f, labelPaint);
        }
    }

    private void drawBar(
        @NonNull Canvas canvas,
        float left,
        float right,
        float bottom,
        float chartHeight,
        float radius,
        long bytes,
        int startColor,
        int endColor,
        float density
    ) {
        float normalized = bytes <= 0L ? 0f : (float) (bytes / (double) maxBytes);
        float barHeight = Math.max(bytes > 0L ? density * 6f : 0f, chartHeight * normalized);
        rect.set(left, bottom - barHeight, right, bottom);
        barPath.reset();
        barPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(barPath);
        canvas.drawRoundRect(rect, radius, radius, slotPaint);
        sentPaint.setShader(
            new LinearGradient(0f, rect.bottom, 0f, rect.top, endColor, startColor, Shader.TileMode.CLAMP)
        );
        canvas.drawRoundRect(rect, radius, radius, sentPaint);
        canvas.restore();
    }
}
