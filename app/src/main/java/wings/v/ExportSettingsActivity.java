package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.core.Haptics;
import wings.v.core.WingsImportParser;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
public class ExportSettingsActivity extends AppCompatActivity {

    private enum ExportAction {
        ALL,
        XRAY,
        VK_TURN,
        WIREGUARD,
        AMNEZIA,
    }

    public static Intent createIntent(final Context context) {
        return new Intent(context, ExportSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_export_settings);
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.export_settings_container, new ExportSettingsFragment())
                .commit();
        }
    }

    private void exportSettings(final ExportAction action) {
        try {
            final String link = buildLink(action);
            final ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboardManager != null) {
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.settings_export_clip_label), link)
                );
            }
            Toast.makeText(this, R.string.settings_export_done, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.settings_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String buildLink(final ExportAction action) throws Exception {
        if (action == ExportAction.XRAY) {
            return WingsImportParser.buildXraySettingsLink(this);
        }
        if (action == ExportAction.VK_TURN) {
            return WingsImportParser.buildVkTurnSettingsLink(this);
        }
        if (action == ExportAction.WIREGUARD) {
            return WingsImportParser.buildWireGuardSettingsLink(this);
        }
        if (action == ExportAction.AMNEZIA) {
            return WingsImportParser.buildAmneziaSettingsLink(this);
        }
        return WingsImportParser.buildAllSettingsLink(this);
    }

    public static class ExportSettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(@Nullable final Bundle savedInstanceState, @Nullable final String rootKey) {
            setPreferencesFromResource(R.xml.export_settings_preferences, rootKey);
            bindExportPreference("pref_export_all_settings", ExportAction.ALL);
            bindExportPreference("pref_export_xray_settings", ExportAction.XRAY);
            bindExportPreference("pref_export_vk_turn_settings", ExportAction.VK_TURN);
            bindExportPreference("pref_export_wireguard_settings", ExportAction.WIREGUARD);
            bindExportPreference("pref_export_amnezia_settings", ExportAction.AMNEZIA);
        }

        private void bindExportPreference(final String key, final ExportAction action) {
            final Preference preference = findPreference(key);
            if (preference == null) {
                return;
            }
            preference.setOnPreferenceClickListener(clickedPreference -> {
                Haptics.softSelection(getListView() != null ? getListView() : requireView());
                ((ExportSettingsActivity) requireActivity()).exportSettings(action);
                return true;
            });
        }
    }
}
