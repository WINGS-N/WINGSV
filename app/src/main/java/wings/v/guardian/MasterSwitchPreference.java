package wings.v.guardian;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

/**
 * Settings-app style "two-target" preference: tapping the row body navigates
 * to a detail screen via the standard {@link androidx.preference.Preference#setOnPreferenceClickListener},
 * while tapping the switch widget itself toggles the bound boolean (calling
 * the change listener and persisting the value the same way as the parent
 * class would on a row click).
 */
public final class MasterSwitchPreference extends SwitchPreferenceCompat {

    public MasterSwitchPreference(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr,
        int defStyleRes
    ) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MasterSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MasterSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MasterSwitchPreference(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        // Row-level click is intercepted by setOnPreferenceClickListener.
        // Skipping the parent toggle keeps the switch from flipping when the
        // user taps the row body to drill into the detail activity.
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View switchWidget = holder.findViewById(androidx.preference.R.id.switchWidget);
        if (switchWidget == null) {
            switchWidget = holder.findViewById(android.R.id.switch_widget);
        }
        if (!(switchWidget instanceof SwitchCompat)) {
            return;
        }
        SwitchCompat sw = (SwitchCompat) switchWidget;
        sw.setClickable(true);
        sw.setFocusable(true);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(isChecked());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (callChangeListener(isChecked)) {
                setChecked(isChecked);
            } else {
                buttonView.setOnCheckedChangeListener(null);
                buttonView.setChecked(!isChecked);
                buttonView.setOnCheckedChangeListener((b, c) -> {});
            }
        });
    }
}
