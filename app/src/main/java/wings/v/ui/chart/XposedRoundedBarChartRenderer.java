package wings.v.ui.chart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import androidx.annotation.NonNull;
import com.github.mikephil.charting.animation.ChartAnimator;
import com.github.mikephil.charting.buffer.BarBuffer;
import com.github.mikephil.charting.interfaces.dataprovider.BarDataProvider;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.renderer.BarChartRenderer;
import com.github.mikephil.charting.utils.Transformer;
import com.github.mikephil.charting.utils.ViewPortHandler;
import wings.v.R;

public final class XposedRoundedBarChartRenderer extends BarChartRenderer {

    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private final int gradientStartColor;
    private final int gradientEndColor;
    private float radius;

    public XposedRoundedBarChartRenderer(
        @NonNull Context context,
        @NonNull BarDataProvider chart,
        @NonNull ChartAnimator animator,
        @NonNull ViewPortHandler viewPortHandler
    ) {
        super(chart, animator, viewPortHandler);
        gradientStartColor = context.getColor(R.color.wingsv_accent);
        gradientEndColor = context.getColor(R.color.wingsv_success);
        outlinePaint.setColor(context.getColor(R.color.wingsv_surface_alt));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        radius = context.getResources().getDisplayMetrics().density * 11f;
    }

    public void setBarRadius(float radius) {
        this.radius = radius;
    }

    @Override
    protected void drawDataSet(@NonNull Canvas canvas, @NonNull IBarDataSet dataSet, int index) {
        if (dataSet.getEntryCount() <= 0 || mBarBuffers == null || index < 0 || index >= mBarBuffers.length) {
            return;
        }
        Transformer transformer = mChart.getTransformer(dataSet.getAxisDependency());
        BarBuffer buffer = mBarBuffers[index];
        if (buffer == null || buffer.buffer == null || buffer.buffer.length == 0) {
            return;
        }
        buffer.setPhases(mAnimator.getPhaseX(), mAnimator.getPhaseY());
        buffer.setDataSet(index);
        buffer.setInverted(mChart.isInverted(dataSet.getAxisDependency()));
        buffer.setBarWidth(mChart.getBarData().getBarWidth());
        buffer.feed(dataSet);
        if (buffer.buffer == null || buffer.buffer.length == 0) {
            return;
        }
        transformer.pointValuesToPixel(buffer.buffer);
        for (int bufferIndex = 0; bufferIndex < buffer.size(); bufferIndex += 4) {
            int rightIndex = bufferIndex + 2;
            if (rightIndex >= buffer.buffer.length || bufferIndex + 3 >= buffer.buffer.length) {
                break;
            }
            if (!mViewPortHandler.isInBoundsLeft(buffer.buffer[rightIndex])) {
                continue;
            }
            if (!mViewPortHandler.isInBoundsRight(buffer.buffer[bufferIndex])) {
                break;
            }
            rect.set(
                buffer.buffer[bufferIndex],
                buffer.buffer[bufferIndex + 1],
                buffer.buffer[rightIndex],
                buffer.buffer[bufferIndex + 3]
            );
            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            mRenderPaint.setColor(dataSet.getColor(bufferIndex / 4));
            canvas.drawRoundRect(rect, radius, radius, mRenderPaint);
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
        }
    }

    @Override
    public void drawValues(@NonNull Canvas canvas) {}
}
