package wings.v.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;
import java.security.SecureRandom;
import wings.v.AmneziaSettingsActivity;
import wings.v.R;
import wings.v.VkLinksActivity;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.XrayStore;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidUsingHardCodedIP" })
public final class WbStreamSettingsFragment extends PreferenceFragmentCompat {

    private static final int E2E_KEY_BYTES = 32;

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
        AppPrefs.KEY_DNS_MODE,
        AppPrefs.KEY_VK_TURN_USER_DNS,
        "pref_category_wb_stream_vk_turn",
        "pref_inset_after_wb_stream_vk_turn",
    };

    private static final String[] WIREGUARD_PREFERENCE_KEYS = {
        "pref_category_wg_interface",
        AppPrefs.KEY_WG_PRIVATE_KEY,
        AppPrefs.KEY_WG_ADDRESSES,
        AppPrefs.KEY_WG_DNS,
        AppPrefs.KEY_WG_MTU,
        "pref_inset_after_wg_interface",
        "pref_category_wg_peer",
        AppPrefs.KEY_WG_PUBLIC_KEY,
        AppPrefs.KEY_WG_PRESHARED_KEY,
        AppPrefs.KEY_WG_ALLOWED_IPS,
        "pref_inset_after_wg_peer",
    };

    private static final String KEY_OPEN_AWG_SETTINGS = "pref_open_wb_stream_awg_settings";

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
        EditTextPreference roomCount = findPreference(AppPrefs.KEY_WB_STREAM_ROOM_COUNT);
        if (roomCount != null) {
            roomCount.setOnBindEditTextListener(text -> text.setInputType(InputType.TYPE_CLASS_NUMBER));
            roomCount.setOnPreferenceChangeListener((preference, newValue) -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(String.valueOf(newValue).trim());
                } catch (NumberFormatException ignored) {
                    parsed = AppPrefs.DEFAULT_WB_STREAM_ROOM_COUNT;
                }
                int clamped = AppPrefs.clampWbStreamRoomCount(parsed);
                AppPrefs.setWbStreamRoomCount(requireContext(), clamped);
                ((EditTextPreference) preference).setText(String.valueOf(clamped));
                return false;
            });
        }
        EditTextPreference e2eSecret = findPreference(AppPrefs.KEY_WB_STREAM_E2E_SECRET);
        if (e2eSecret != null) {
            e2eSecret.setOnBindEditTextListener(text -> text.setInputType(InputType.TYPE_CLASS_TEXT));
        }

        androidx.preference.ListPreference dnsMode = findPreference(AppPrefs.KEY_DNS_MODE);
        if (dnsMode != null) {
            dnsMode.setSummaryProvider(androidx.preference.ListPreference.SimpleSummaryProvider.getInstance());
        }

        EditTextPreference userDns = findPreference(AppPrefs.KEY_VK_TURN_USER_DNS);
        if (userDns != null) {
            userDns.setOnBindEditTextListener(text -> {
                text.setSingleLine(false);
                text.setMinLines(3);
                text.setInputType(
                    InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                );
            });
        }

        Preference openVkLinks = findPreference(AppPrefs.KEY_OPEN_VK_LINKS);
        if (openVkLinks != null) {
            openVkLinks.setOnPreferenceClickListener(preference -> {
                startActivity(VkLinksActivity.createIntent(requireContext()));
                return true;
            });
        }

        Preference openAwgSettings = findPreference(KEY_OPEN_AWG_SETTINGS);
        if (openAwgSettings != null) {
            openAwgSettings.setOnPreferenceClickListener(preference -> {
                startActivity(AmneziaSettingsActivity.createIntent(requireContext()));
                return true;
            });
        }

        applyVisibility();

        listener = (sharedPreferences, key) -> {
            if (AppPrefs.KEY_WB_STREAM_E2E_ENABLED.equals(key)) {
                ensureE2eSecret();
            }
            if (
                AppPrefs.KEY_WB_STREAM_EXCHANGE_VIA_VK_TURN.equals(key) ||
                AppPrefs.KEY_WB_STREAM_E2E_ENABLED.equals(key) ||
                AppPrefs.KEY_BACKEND_TYPE.equals(key)
            ) {
                applyVisibility();
            }
        };
    }

    private void ensureE2eSecret() {
        if (!AppPrefs.isWbStreamE2eEnabled(requireContext())) {
            return;
        }
        if (!TextUtils.isEmpty(AppPrefs.getWbStreamE2eSecret(requireContext()))) {
            return;
        }
        byte[] key = new byte[E2E_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.encodeToString(key, Base64.NO_WRAP);
        AppPrefs.setWbStreamE2eSecret(requireContext(), encoded);
        EditTextPreference e2eSecret = findPreference(AppPrefs.KEY_WB_STREAM_E2E_SECRET);
        if (e2eSecret != null) {
            e2eSecret.setText(encoded);
        }
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
        BackendType backendType = XrayStore.getBackendType(requireContext());
        boolean awgMode = backendType == BackendType.WB_STREAM_AMNEZIAWG;
        for (String key : WIREGUARD_PREFERENCE_KEYS) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setVisible(!awgMode);
            }
        }
        Preference openAwgSettings = findPreference(KEY_OPEN_AWG_SETTINGS);
        if (openAwgSettings != null) {
            openAwgSettings.setVisible(awgMode);
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
