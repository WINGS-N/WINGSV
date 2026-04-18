package wings.v.ui.chart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetrics;
import androidx.annotation.NonNull;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.renderer.XAxisRenderer;
import com.github.mikephil.charting.utils.MPPointF;
import com.github.mikephil.charting.utils.Transformer;
import com.github.mikephil.charting.utils.ViewPortHandler;
import wings.v.R;

public final class XposedWeeklyXAxisRenderer extends XAxisRenderer {

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedLabelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float labelMargin;
    private final float labelBgRadius;
    private final float labelBgYOffset;
    private int labelCount;
    private int selectedIndex = -1;

    public XposedWeeklyXAxisRenderer(
        @NonNull ViewPortHandler viewPortHandler,
        @NonNull XAxis xAxis,
        @NonNull Transformer transformer,
        @NonNull Context context
    ) {
        super(viewPortHandler, xAxis, transformer);
        float textSize = context.getResources().getDisplayMetrics().scaledDensity * 11f;
        labelPaint.setColor(context.getColor(R.color.wingsv_text_secondary));
        labelPaint.setTextSize(textSize);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        selectedLabelPaint.setColor(context.getColor(R.color.wingsv_window));
        selectedLabelPaint.setTextSize(textSize);
        selectedLabelPaint.setTextAlign(Paint.Align.CENTER);
        selectedLabelPaint.setFakeBoldText(true);
        labelBgPaint.setColor(context.getColor(R.color.wingsv_surface_chip_dim));
        selectedLabelBgPaint.setColor(context.getColor(R.color.wingsv_accent));
        labelMargin = context.getResources().getDisplayMetrics().density * 2f;
        labelBgRadius = context.getResources().getDisplayMetrics().density * 13f;
        labelBgYOffset = context.getResources().getDisplayMetrics().density * 1f;
    }

    public void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
    }

    public void setLabelCount(int labelCount) {
        this.labelCount = Math.max(labelCount, 0);
    }

    @Override
    protected void drawLabels(@NonNull Canvas canvas, float pos, @NonNull MPPointF anchor) {
        int entryCount = labelCount > 0 ? labelCount : mXAxis.mEntryCount;
        if (entryCount <= 0) {
            return;
        }
        float[] positions = new float[entryCount * 2];
        for (int index = 0; index < positions.length; index += 2) {
            positions[index] = index / 2f;
        }
        mTrans.pointValuesToPixel(positions);
        float labelCenterY = pos + labelMargin + labelBgYOffset;
        for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            float x = positions[entryIndex * 2];
            if (!mViewPortHandler.isInBoundsX(x)) {
                continue;
            }
            String label = mXAxis.getValueFormatter().getAxisLabel(entryIndex, mXAxis);
            Paint textPaint;
            if (entryIndex == selectedIndex) {
                canvas.drawCircle(x, labelCenterY, labelBgRadius, selectedLabelBgPaint);
                textPaint = selectedLabelPaint;
            } else {
                canvas.drawCircle(x, labelCenterY, labelBgRadius, labelBgPaint);
                textPaint = labelPaint;
            }
            FontMetrics metrics = textPaint.getFontMetrics();
            float textBaseline = labelCenterY - ((metrics.ascent + metrics.descent) * 0.5f);
            canvas.drawText(label, x, textBaseline, textPaint);
        }
    }
}
