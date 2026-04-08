package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;
import wings.v.core.XrayRoutingRule;
import wings.v.core.XrayRoutingStore;
import wings.v.databinding.ActivityXrayRoutingSettingsBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AvoidCatchingGenericException",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.CouplingBetweenObjects",
        "PMD.DoNotUseThreads",
        "PMD.AtLeastOneConstructor",
        "PMD.LongVariable",
        "PMD.ShortVariable",
        "PMD.NullAssignment",
        "PMD.LooseCoupling",
        "PMD.AvoidDuplicateLiterals",
        "PMD.CommentDefaultAccessModifier",
        "PMD.UncommentedEmptyMethodBody",
        "PMD.OnlyOneReturn",
    }
)
public class XrayRoutingSettingsActivity extends AppCompatActivity {

    private ActivityXrayRoutingSettingsBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> openDocumentLauncher = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(),
        this::onGeoFilePicked
    );
    private XrayRoutingRule.MatchType pendingImportType;
    private boolean geoipDownloading;
    private boolean geositeDownloading;

    public static Intent createIntent(Context context) {
        return new Intent(context, XrayRoutingSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityXrayRoutingSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        XrayRoutingStore.ensureGeoFilesBootstrap(this);
        bindGeoActions();
        binding.entryRules.setOnClickListener(v -> {
            Haptics.softSelection(v);
            startActivity(XrayRoutingRulesActivity.createIntent(this));
        });
        renderAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindGeoActions() {
        binding.buttonGeoipEditUrl.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showUrlDialog(XrayRoutingRule.MatchType.GEOIP);
        });
        binding.buttonGeoipDownload.setOnClickListener(v -> {
            Haptics.softSelection(v);
            downloadGeoFile(XrayRoutingRule.MatchType.GEOIP);
        });
        binding.buttonGeoipImport.setOnClickListener(v -> {
            Haptics.softSelection(v);
            importGeoFile(XrayRoutingRule.MatchType.GEOIP);
        });

        binding.buttonGeositeEditUrl.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showUrlDialog(XrayRoutingRule.MatchType.GEOSITE);
        });
        binding.buttonGeositeDownload.setOnClickListener(v -> {
            Haptics.softSelection(v);
            downloadGeoFile(XrayRoutingRule.MatchType.GEOSITE);
        });
        binding.buttonGeositeImport.setOnClickListener(v -> {
            Haptics.softSelection(v);
            importGeoFile(XrayRoutingRule.MatchType.GEOSITE);
        });
    }

    private void renderAll() {
        renderGeoCard(XrayRoutingRule.MatchType.GEOIP);
        renderGeoCard(XrayRoutingRule.MatchType.GEOSITE);
    }

    private void renderGeoCard(XrayRoutingRule.MatchType matchType) {
        XrayRoutingStore.GeoFileInfo info = XrayRoutingStore.getGeoFileInfo(this, matchType);
        String sourceValue = getString(
            R.string.xray_routing_source_value,
            XrayRoutingStore.getSourceUrl(this, matchType)
        );
        boolean downloading = isDownloadInProgress(matchType);
        if (matchType == XrayRoutingRule.MatchType.GEOIP) {
            binding.textGeoipSource.setText(sourceValue);
            binding.progressGeoipDownload.setVisibility(
                downloading ? android.view.View.VISIBLE : android.view.View.GONE
            );
            binding.buttonGeoipDownload.setEnabled(!downloading);
            binding.buttonGeoipEditUrl.setEnabled(!downloading);
            binding.buttonGeoipImport.setEnabled(!downloading);
            if (downloading) {
                binding.textGeoipStatus.setText(R.string.xray_routing_downloading);
                binding.textGeoipBadge.setText(R.string.xray_routing_badge_missing);
                binding.textGeoipBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
                return;
            }
            if (info.exists) {
                binding.textGeoipStatus.setText(
                    getString(R.string.xray_routing_file_ready, info.categoryCount) +
                        " · " +
                        UiFormatter.formatBytes(this, info.sizeBytes)
                );
                binding.textGeoipBadge.setText(R.string.xray_routing_badge_ready);
                binding.textGeoipBadge.setBackgroundResource(R.drawable.bg_profile_ping_good);
            } else {
                binding.textGeoipStatus.setText(R.string.xray_routing_file_missing);
                binding.textGeoipBadge.setText(R.string.xray_routing_badge_missing);
                binding.textGeoipBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
            }
            return;
        }

        binding.textGeositeSource.setText(sourceValue);
        binding.progressGeositeDownload.setVisibility(downloading ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.buttonGeositeDownload.setEnabled(!downloading);
        binding.buttonGeositeEditUrl.setEnabled(!downloading);
        binding.buttonGeositeImport.setEnabled(!downloading);
        if (downloading) {
            binding.textGeositeStatus.setText(R.string.xray_routing_downloading);
            binding.textGeositeBadge.setText(R.string.xray_routing_badge_missing);
            binding.textGeositeBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
            return;
        }
        if (info.exists) {
            binding.textGeositeStatus.setText(
                getString(R.string.xray_routing_file_ready, info.categoryCount) +
                    " · " +
                    UiFormatter.formatBytes(this, info.sizeBytes)
            );
            binding.textGeositeBadge.setText(R.string.xray_routing_badge_ready);
            binding.textGeositeBadge.setBackgroundResource(R.drawable.bg_profile_ping_good);
        } else {
            binding.textGeositeStatus.setText(R.string.xray_routing_file_missing);
            binding.textGeositeBadge.setText(R.string.xray_routing_badge_missing);
            binding.textGeositeBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
        }
    }

    private void showUrlDialog(XrayRoutingRule.MatchType matchType) {
        EditText input = new EditText(this);
        input.setText(XrayRoutingStore.getSourceUrl(this, matchType));
        input.setSelection(input.getText().length());
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(
                matchType == XrayRoutingRule.MatchType.GEOSITE
                    ? R.string.xray_routing_url_dialog_title_geosite
                    : R.string.xray_routing_url_dialog_title_geoip
            )
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.xray_routing_url_reset, null)
            .create();
        dialog.setOnShowListener(ignored -> {
            dialog
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    Haptics.softSelection(v);
                    input.setText(
                        matchType == XrayRoutingRule.MatchType.GEOSITE
                            ? XrayRoutingStore.DEFAULT_GEOSITE_URL
                            : XrayRoutingStore.DEFAULT_GEOIP_URL
                    );
                    input.setSelection(input.getText().length());
                });
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Haptics.softConfirm(v);
                    XrayRoutingStore.setSourceUrl(this, matchType, input.getText().toString());
                    renderGeoCard(matchType);
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    private void importGeoFile(XrayRoutingRule.MatchType matchType) {
        pendingImportType = matchType;
        openDocumentLauncher.launch(new String[] { "*/*" });
    }

    private void onGeoFilePicked(Uri uri) {
        if (uri == null || pendingImportType == null) {
            return;
        }
        XrayRoutingRule.MatchType matchType = pendingImportType;
        pendingImportType = null;
        executor.execute(() -> {
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                XrayRoutingStore.importGeoFile(this, matchType, inputStream);
                runOnUiThread(() -> {
                    renderAll();
                    toast(
                        getString(
                            R.string.xray_routing_import_done,
                            matchType == XrayRoutingRule.MatchType.GEOSITE
                                ? getString(R.string.xray_routing_geosite_title)
                                : getString(R.string.xray_routing_geoip_title)
                        )
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() ->
                    toast(
                        getString(
                            R.string.xray_routing_operation_failed,
                            matchType == XrayRoutingRule.MatchType.GEOSITE
                                ? getString(R.string.xray_routing_geosite_title)
                                : getString(R.string.xray_routing_geoip_title),
                            error.getMessage()
                        )
                    )
                );
            }
        });
    }

    private void downloadGeoFile(XrayRoutingRule.MatchType matchType) {
        String fileLabel =
            matchType == XrayRoutingRule.MatchType.GEOSITE
                ? getString(R.string.xray_routing_geosite_title)
                : getString(R.string.xray_routing_geoip_title);
        setDownloadInProgress(matchType, true);
        toast(getString(R.string.xray_routing_download_started, fileLabel));
        executor.execute(() -> {
            try {
                XrayRoutingStore.downloadGeoFile(this, matchType, XrayRoutingStore.getSourceUrl(this, matchType));
                runOnUiThread(() -> {
                    setDownloadInProgress(matchType, false);
                    renderAll();
                    toast(getString(R.string.xray_routing_download_done, fileLabel));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setDownloadInProgress(matchType, false);
                    renderAll();
                    toast(getString(R.string.xray_routing_operation_failed, fileLabel, error.getMessage()));
                });
            }
        });
    }

    private void setDownloadInProgress(XrayRoutingRule.MatchType matchType, boolean inProgress) {
        if (matchType == XrayRoutingRule.MatchType.GEOSITE) {
            geositeDownloading = inProgress;
        } else {
            geoipDownloading = inProgress;
        }
        renderGeoCard(matchType);
    }

    private boolean isDownloadInProgress(XrayRoutingRule.MatchType matchType) {
        return matchType == XrayRoutingRule.MatchType.GEOSITE ? geositeDownloading : geoipDownloading;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
