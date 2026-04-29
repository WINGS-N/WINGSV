package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import wings.v.R;
import wings.v.VkLinksActivity;
import wings.v.core.AppPrefs;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidUsingHardCodedIP" })
public final class WbStreamSettingsFragment extends PreferenceFragmentCompat {

    private static final String[] VK_TURN_PREFERENCE_KEYS = {
        AppPrefs.KEY_ENDPOINT,
        AppPrefs.KEY_OPEN_VK_LINKS,
        AppPrefs.KEY_THREADS,
        AppPrefs.KEY_CREDS_GROUP_SIZE,
        AppPrefs.KEY_USE_UDP,
        AppPrefs.KEY_NO_OBFUSCATION,
        AppPrefs.KEY_MANUAL_CAPTCHA,
        AppPrefs.KEY_CAPTCHA_AUTO_SOLVER,
        AppPrefs.KEY_TURN_SESSION_MODE,
        AppPrefs.KEY_LOCAL_ENDPOINT,
        AppPrefs.KEY_TURN_HOST,
        AppPrefs.KEY_TURN_PORT,
        "pref_category_wb_stream_vk_turn",
        "pref_inset_after_wb_stream_vk_turn",
    };

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.wb_stream_preferences, rootKey);

        EditTextPreference roomId = findPreference(AppPrefs.KEY_WB_STREAM_ROOM_ID);
        if (roomId != null) {
            roomId.setOnBindEditTextListener(text -> text.setInputType(InputType.TYPE_CLASS_TEXT));
        }
        EditTextPreference displayName = findPreference(AppPrefs.KEY_WB_STREAM_DISPLAY_NAME);
        if (displayName != null) {
            displayName.setOnBindEditTextListener(text -> text.setInputType(InputType.TYPE_CLASS_TEXT));
        }
        EditTextPreference e2eSecret = findPreference(AppPrefs.KEY_WB_STREAM_E2E_SECRET);
        if (e2eSecret != null) {
            e2eSecret.setOnBindEditTextListener(text ->
                text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            );
        }

        Preference openVkLinks = findPreference(AppPrefs.KEY_OPEN_VK_LINKS);
        if (openVkLinks != null) {
            openVkLinks.setOnPreferenceClickListener(preference -> {
                startActivity(VkLinksActivity.createIntent(requireContext()));
                return true;
            });
        }

        applyVisibility();

        listener = (sharedPreferences, key) -> {
            if (
                AppPrefs.KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN.equals(key) ||
                AppPrefs.KEY_WB_STREAM_E2E_ENABLED.equals(key)
            ) {
                applyVisibility();
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
        applyVisibility();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void applyVisibility() {
        boolean exchangeViaVkTurn = AppPrefs.isWbStreamExchangeViaVkTurn(requireContext());
        for (String key : VK_TURN_PREFERENCE_KEYS) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setVisible(exchangeViaVkTurn);
            }
        }
        SwitchPreferenceCompat e2eEnabled = findPreference(AppPrefs.KEY_WB_STREAM_E2E_ENABLED);
        EditTextPreference e2eSecret = findPreference(AppPrefs.KEY_WB_STREAM_E2E_SECRET);
        if (e2eEnabled != null && e2eSecret != null) {
            e2eSecret.setVisible(e2eEnabled.isChecked());
        }
    }

    @NonNull
    public static WbStreamSettingsFragment newInstance() {
        return new WbStreamSettingsFragment();
    }
}
