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
 * Two-target preference in the Settings-app shape: the row body goes to a detail screen
 * through the usual {@link androidx.preference.Preference#setOnPreferenceClickListener},
 * and only the switch widget toggles the bound boolean
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
        // setOnPreferenceClickListener owns the row click; letting the parent toggle run
        // would flip the switch while the user is only drilling into the detail screen
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
