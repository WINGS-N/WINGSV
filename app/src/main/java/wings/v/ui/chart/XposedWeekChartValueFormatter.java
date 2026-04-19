package wings.v.ui.chart;

import androidx.annotation.NonNull;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.formatter.ValueFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class XposedWeekChartValueFormatter extends ValueFormatter {

    @NonNull
    private final List<String> labels = new ArrayList<>();

    public void setLabels(@NonNull List<String> labels, boolean isRtl) {
        this.labels.clear();
        this.labels.addAll(labels);
        if (isRtl) {
            Collections.reverse(this.labels);
        }
    }

    @NonNull
    @Override
    public String getAxisLabel(float value, AxisBase axis) {
        int index = Math.round(value);
        if (index < 0 || index >= labels.size()) {
            return "";
        }
        return labels.get(index);
    }
}
