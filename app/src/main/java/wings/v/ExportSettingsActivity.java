package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.core.AppPrefs;
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
        WB_STREAM,
        XPOSED,
        APP_ROUTING_BYPASS,
        XRAY_ROUTING,
    }

    private final ActivityResultLauncher<Intent> allExportWarningLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK) {
                performExport(ExportAction.ALL);
            }
        }
    );

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
        if (action == ExportAction.ALL) {
            allExportWarningLauncher.launch(
                WarningConfirmActivity.createIntent(
                    this,
                    getString(R.string.settings_export_telegram_markdown_warning),
                    2
                )
            );
            return;
        }
        performExport(action);
    }

    private void performExport(final ExportAction action) {
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
        if (action == ExportAction.WB_STREAM) {
            return WingsImportParser.buildWbStreamSettingsLink(this);
        }
        if (action == ExportAction.XPOSED) {
            return WingsImportParser.buildXposedSettingsLink(this);
        }
        if (action == ExportAction.APP_ROUTING_BYPASS) {
            return WingsImportParser.buildAppRoutingBypassLink(this);
        }
        if (action == ExportAction.XRAY_ROUTING) {
            return WingsImportParser.buildXrayRoutingLink(this);
        }
        return WingsImportParser.buildAllSettingsLink(this);
    }

    public static class ExportSettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(@Nullable final Bundle savedInstanceState, @Nullable final String rootKey) {
            getPreferenceManager().setPreferenceDataStore(AppPrefs.mainPreferenceDataStore(requireContext()));
            setPreferencesFromResource(R.xml.export_settings_preferences, rootKey);
            bindExportPreference("pref_export_all_settings", ExportAction.ALL);
            bindExportPreference("pref_export_xray_settings", ExportAction.XRAY);
            bindExportPreference("pref_export_vk_turn_settings", ExportAction.VK_TURN);
            bindExportPreference("pref_export_wireguard_settings", ExportAction.WIREGUARD);
            bindExportPreference("pref_export_amnezia_settings", ExportAction.AMNEZIA);
            bindExportPreference("pref_export_wb_stream_settings", ExportAction.WB_STREAM);
            bindExportPreference("pref_export_xposed_settings", ExportAction.XPOSED);
            bindExportPreference("pref_export_app_routing_bypass", ExportAction.APP_ROUTING_BYPASS);
            bindExportPreference("pref_export_xray_routing", ExportAction.XRAY_ROUTING);
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
