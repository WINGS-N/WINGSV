package wings.v.ui.chart;

import android.content.Context;
import android.view.View;
import android.view.animation.Interpolator;
import androidx.annotation.NonNull;
import androidx.core.text.TextUtilsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.animation.PathInterpolatorCompat;
import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import wings.v.R;
import wings.v.core.DisplayDensityUtils;
import wings.v.core.XposedAttackStatsStore;

public final class XposedWeeklyChartConfigurator {

    private static final Easing.EasingFunction SAMSUNG_CHART_EASING = new Easing.EasingFunction() {
        private final Interpolator interpolator = PathInterpolatorCompat.create(0.33f, 0.0f, 0.1f, 1.0f);

        @Override
        public float getInterpolation(float input) {
            return interpolator.getInterpolation(input);
        }
    };

    private XposedWeeklyChartConfigurator() {}

    public static void bind(@NonNull BarChart chart, @NonNull List<XposedAttackStatsStore.DailyPoint> points) {
        Context context = chart.getContext();
        
        // Safe density calculation
        float density = 1.0f;
        try {
            float rawDensity = context.getResources().getDisplayMetrics().density;
            if (DisplayDensityUtils.isValidDensity(rawDensity)) {
                density = rawDensity;
            }
        } catch (Exception e) {
            android.util.Log.w("XposedWeeklyChartConfigurator", "Error getting density, using default", e);
        }
        
        boolean isRtl =
            TextUtilsCompat.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL ||
            ViewCompat.getLayoutDirection(chart) == ViewCompat.LAYOUT_DIRECTION_RTL;
        chart.setDescription(null);
        chart.setDrawGridBackground(false);
        chart.setScaleEnabled(false);
        chart.setDoubleTapToZoomEnabled(false);
        chart.setDragEnabled(false);
        chart.setPinchZoom(false);
        chart.setDrawValueAboveBar(false);
        chart.setDrawBarShadow(false);
        chart.setHighlightPerTapEnabled(false);
        chart.setHighlightPerDragEnabled(false);
        chart.setNoDataText("");
        chart.clearAnimation();
        chart.fitScreen();
        chart.setMinOffset(0f);

        Legend legend = chart.getLegend();
        legend.setEnabled(false);

        XAxis xAxis = chart.getXAxis();
        xAxis.setEnabled(true);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setLabelCount(Math.max(points.size(), 1), true);
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);
        xAxis.setTextSize(11f);
        xAxis.setTextColor(context.getColor(R.color.wingsv_text_secondary));
        xAxis.setYOffset(DisplayDensityUtils.dpToPx(context, 2));
        xAxis.setAvoidFirstLastClipping(false);
        xAxis.setCenterAxisLabels(false);
        xAxis.setLabelRotationAngle(0f);

        YAxis leftAxis = chart.getAxisLeft();
        YAxis rightAxis = chart.getAxisRight();
        if (isRtl) {
            leftAxis.setEnabled(true);
            configureValueAxis(leftAxis, context);
            rightAxis.setEnabled(false);
        } else {
            rightAxis.setEnabled(true);
            configureValueAxis(rightAxis, context);
            leftAxis.setEnabled(false);
        }

        List<BarEntry> entries = new ArrayList<>(points.size());
        List<String> labels = new ArrayList<>(points.size());
        int max = 0;
        for (int index = 0; index < points.size(); index++) {
            XposedAttackStatsStore.DailyPoint point = points.get(index);
            entries.add(new BarEntry(index, point.count));
            labels.add(point.getWeekLabel());
            max = Math.max(max, point.count);
        }
        if (isRtl) {
            java.util.Collections.reverse(entries);
            for (int index = 0; index < entries.size(); index++) {
                entries.get(index).setX(index);
            }
        }
        XposedWeekChartValueFormatter formatter = new XposedWeekChartValueFormatter();
        formatter.setLabels(labels, isRtl);
        xAxis.setValueFormatter(formatter);

        BarDataSet dataSet = new BarDataSet(entries, "");
        dataSet.setColor(context.getColor(R.color.wingsv_surface_alt));
        dataSet.setDrawValues(false);
        dataSet.setHighlightEnabled(false);
        BarData data = new BarData(dataSet);
        data.setBarWidth(0.4f);
        chart.setData(data);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(Math.max(points.size() - 0.5f, 0.5f));

        float axisMax = Math.max(max, 1);
        if (isRtl) {
            leftAxis.setAxisMinimum(0f);
            leftAxis.setAxisMaximum(axisMax);
        } else {
            rightAxis.setAxisMinimum(0f);
            rightAxis.setAxisMaximum(axisMax);
        }

        XposedRoundedBarChartRenderer renderer = new XposedRoundedBarChartRenderer(
            context,
            chart,
            chart.getAnimator(),
            chart.getViewPortHandler()
        );
        renderer.setBarRadius(DisplayDensityUtils.dpToPx(context, 11));
        XposedWeeklyXAxisRenderer xAxisRenderer = new XposedWeeklyXAxisRenderer(
            chart.getViewPortHandler(),
            xAxis,
            chart.getTransformer(isRtl ? YAxis.AxisDependency.LEFT : YAxis.AxisDependency.RIGHT),
            context
        );
        xAxisRenderer.setLabelCount(entries.size());
        xAxisRenderer.setSelectedIndex(entries.isEmpty() ? -1 : (isRtl ? 0 : entries.size() - 1));
        chart.setRenderer(renderer);
        chart.setXAxisRenderer(xAxisRenderer);
        chart.notifyDataSetChanged();
        chart.post(() -> {
            float leftOffset = isRtl ? DisplayDensityUtils.dpToPx(context, 30) : DisplayDensityUtils.dpToPx(context, 12);
            float rightOffset = isRtl ? DisplayDensityUtils.dpToPx(context, 10) : DisplayDensityUtils.dpToPx(context, 36);
            chart.setViewPortOffsets(leftOffset, DisplayDensityUtils.dpToPx(context, 8), rightOffset, DisplayDensityUtils.dpToPx(context, 34));
            chart.notifyDataSetChanged();
            chart.animateY(500, SAMSUNG_CHART_EASING);
            chart.invalidate();
        });
    }

    private static void configureValueAxis(@NonNull YAxis axis, @NonNull Context context) {
        axis.setDrawGridLines(false);
        axis.setDrawAxisLine(false);
        axis.setDrawZeroLine(false);
        axis.setTextColor(context.getColor(R.color.wingsv_text_secondary));
        axis.setTextSize(11f);
        axis.setXOffset(DisplayDensityUtils.dpToPx(context, 2));
        axis.setYOffset(DisplayDensityUtils.dpToPx(context, 3));
        axis.setLabelCount(3, true);
        axis.setValueFormatter(
            new ValueFormatter() {
                @NonNull
                @Override
                public String getFormattedValue(float value) {
                    int rounded = Math.round(value);
                    if (rounded <= 0) {
                        return "";
                    }
                    if (rounded < 1000) {
                        return String.valueOf(rounded);
                    }
                    float compact = rounded / 1000f;
                    if (compact >= 10f || Math.abs(compact - Math.round(compact)) < 0.05f) {
                        return Math.round(compact) + "K";
                    }
                    return String.format(Locale.US, "%.1fK", compact);
                }
            }
        );
    }
}
