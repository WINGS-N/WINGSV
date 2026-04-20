package wings.v.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.picker.widget.SeslTimePicker;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import java.util.Locale;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.XrayStore;

public class SubscriptionUpdateSettingsFragment extends PreferenceFragmentCompat {

    private static final int MAX_PICKER_REFRESH_INTERVAL_MINUTES = 24 * 60;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        AppPrefs.ensureDefaults(requireContext());
        setPreferencesFromResource(R.xml.subscription_update_preferences, rootKey);
        configurePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncFromStore();
    }

    public static String formatRefreshIntervalMinutes(int minutes) {
        int normalizedMinutes = normalizeRefreshIntervalMinutes(minutes);
        if (normalizedMinutes >= MAX_PICKER_REFRESH_INTERVAL_MINUTES) {
            return "24:00";
        }
        int hoursPart = normalizedMinutes / 60;
        int minutesPart = normalizedMinutes % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", hoursPart, minutesPart);
    }

    private void configurePreferences() {
        SwitchPreferenceCompat autoRefreshPreference = findPreference(
            AppPrefs.KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED
        );
        if (autoRefreshPreference != null) {
            autoRefreshPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Haptics.softSliderStep(getListView() != null ? getListView() : requireView());
                XrayStore.setSubscriptionsAutoRefreshEnabled(requireContext(), Boolean.TRUE.equals(newValue));
                syncFromStore();
                return false;
            });
        }
        Preference intervalPreference = findPreference(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES);
        if (intervalPreference != null) {
            intervalPreference.setOnPreferenceClickListener(preference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                showRefreshIntervalDialog();
                return true;
            });
        }
    }

    private void syncFromStore() {
        boolean enabled = XrayStore.isSubscriptionsAutoRefreshEnabled(requireContext());
        SwitchPreferenceCompat autoRefreshPreference = findPreference(
            AppPrefs.KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED
        );
        if (autoRefreshPreference != null && autoRefreshPreference.isChecked() != enabled) {
            autoRefreshPreference.setChecked(enabled);
        }
        Preference intervalPreference = findPreference(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_REFRESH_MINUTES);
        if (intervalPreference != null) {
            intervalPreference.setEnabled(enabled);
            intervalPreference.setSummary(
                enabled
                    ? getString(
                          R.string.xray_subscriptions_refresh_interval_summary,
                          formatRefreshIntervalMinutes(XrayStore.getRefreshIntervalMinutes(requireContext()))
                      )
                    : getString(R.string.xray_subscriptions_refresh_interval_summary_disabled)
            );
        }
    }

    private void showRefreshIntervalDialog() {
        int currentMinutes = normalizeRefreshIntervalMinutes(XrayStore.getRefreshIntervalMinutes(requireContext()));
        View dialogView = LayoutInflater.from(requireContext()).inflate(
            R.layout.dialog_subscription_refresh_interval,
            null,
            false
        );
        FrameLayout pickerContainer = dialogView.findViewById(R.id.container_refresh_interval_picker);
        SeslTimePicker timePicker = buildRefreshIntervalTimePicker(currentMinutes);
        pickerContainer.addView(timePicker);
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.xray_subscriptions_refresh_interval_title)
            .setView(dialogView)
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(R.string.sharing_edit_dialog_save, (dialog, which) -> {
                timePicker.clearFocus();
                XrayStore.setRefreshIntervalMinutes(
                    requireContext(),
                    pickerTimeToRefreshIntervalMinutes(timePicker.getHour(), timePicker.getMinute())
                );
                syncFromStore();
            })
            .show();
    }

    private SeslTimePicker buildRefreshIntervalTimePicker(int currentMinutes) {
        SeslTimePicker timePicker = new SeslTimePicker(
            new ContextThemeWrapper(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_DayNight)
        );
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        timePicker.setLayoutParams(layoutParams);
        timePicker.setIs24HourView(Boolean.TRUE);
        int initialMinutes = currentMinutes >= MAX_PICKER_REFRESH_INTERVAL_MINUTES ? 0 : currentMinutes;
        timePicker.setHour(initialMinutes / 60);
        timePicker.setMinute(initialMinutes % 60);
        final int[] lastValue = { initialMinutes };
        timePicker.setOnTimeChangedListener((view, hourOfDay, minute) -> {
            int currentValue = hourOfDay * 60 + minute;
            if (lastValue[0] != currentValue) {
                Haptics.softSliderStep(view);
                lastValue[0] = currentValue;
            }
        });
        return timePicker;
    }

    private static int normalizeRefreshIntervalMinutes(int minutes) {
        if (minutes <= 0) {
            return MAX_PICKER_REFRESH_INTERVAL_MINUTES;
        }
        return Math.min(minutes, MAX_PICKER_REFRESH_INTERVAL_MINUTES);
    }

    private int pickerTimeToRefreshIntervalMinutes(int hourOfDay, int minute) {
        int totalMinutes = Math.max(0, hourOfDay) * 60 + Math.max(0, minute);
        if (totalMinutes <= 0) {
            return MAX_PICKER_REFRESH_INTERVAL_MINUTES;
        }
        return normalizeRefreshIntervalMinutes(totalMinutes);
    }
}
