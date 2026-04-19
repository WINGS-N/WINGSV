package wings.v.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.github.mikephil.charting.charts.BarChart;
import java.util.Collections;
import wings.v.R;
import wings.v.core.XposedAttackStatsStore;
import wings.v.core.XposedSecurityScore;
import wings.v.ui.chart.XposedWeeklyChartConfigurator;
import wings.v.widget.XposedSecurityLevelBarView;

public class XposedSecurityOverviewPreference extends Preference {

    @NonNull
    private XposedSecurityScore.Snapshot snapshot = new XposedSecurityScore.Snapshot(
        0,
        XposedSecurityScore.Level.WEAK,
        ""
    );

    @NonNull
    private XposedAttackStatsStore.WeeklySummary weeklySummary = new XposedAttackStatsStore.WeeklySummary(
        Collections.emptyList(),
        0,
        0
    );

    @Nullable
    private View.OnClickListener historyClickListener;

    public XposedSecurityOverviewPreference(@NonNull Context context) {
        this(context, null);
    }

    public XposedSecurityOverviewPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public XposedSecurityOverviewPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        setLayoutResource(R.layout.preference_xposed_security_overview);
        setSelectable(false);
    }

    public void bindState(
        @NonNull XposedSecurityScore.Snapshot snapshot,
        @NonNull XposedAttackStatsStore.WeeklySummary weeklySummary
    ) {
        this.snapshot = snapshot;
        this.weeklySummary = weeklySummary;
        notifyChanged();
    }

    public void setOnHistoryClickListener(@Nullable View.OnClickListener historyClickListener) {
        this.historyClickListener = historyClickListener;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context context = getContext();
        TextView levelView = (TextView) holder.findViewById(R.id.text_xposed_security_level);
        TextView scoreView = (TextView) holder.findViewById(R.id.text_xposed_security_score);
        TextView hintView = (TextView) holder.findViewById(R.id.text_xposed_security_hint);
        XposedSecurityLevelBarView levelBarView = (XposedSecurityLevelBarView) holder.findViewById(
            R.id.view_xposed_security_bar
        );
        TextView summaryView = (TextView) holder.findViewById(R.id.text_xposed_weekly_summary);
        BarChart graphView = (BarChart) holder.findViewById(R.id.view_xposed_weekly_graph);
        View historyRow = holder.findViewById(R.id.row_xposed_history);

        if (levelView != null) {
            levelView.setText(getLevelLabel(context, snapshot.level));
        }
        if (scoreView != null) {
            scoreView.setText(context.getString(R.string.xposed_security_score_value, snapshot.score));
        }
        if (hintView != null) {
            hintView.setText(
                TextUtils.isEmpty(snapshot.hint)
                    ? context.getString(R.string.xposed_security_hint_default)
                    : snapshot.hint
            );
        }
        if (levelBarView != null) {
            levelBarView.setState(snapshot.score, snapshot.level);
        }
        if (summaryView != null) {
            summaryView.setText(
                context.getString(
                    R.string.xposed_weekly_summary_value,
                    weeklySummary.totalCount,
                    weeklySummary.todayCount
                )
            );
        }
        if (graphView != null) {
            XposedWeeklyChartConfigurator.bind(graphView, weeklySummary.points);
        }
        if (historyRow != null) {
            historyRow.setOnClickListener(historyClickListener);
        }
    }

    @NonNull
    private String getLevelLabel(@NonNull Context context, @NonNull XposedSecurityScore.Level level) {
        switch (level) {
            case MAXIMUM:
                return context.getString(R.string.xposed_security_level_maximum);
            case MEDIUM:
                return context.getString(R.string.xposed_security_level_medium);
            case WEAK:
            default:
                return context.getString(R.string.xposed_security_level_weak);
        }
    }
}
