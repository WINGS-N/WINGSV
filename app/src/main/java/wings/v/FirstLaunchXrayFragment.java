package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.fragment.app.Fragment;
import dev.oneuiproject.oneui.qr.app.QrScanActivity;
import java.util.Locale;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.WingsImportParser;
import wings.v.core.XraySettings;
import wings.v.core.XrayStore;
import wings.v.databinding.FragmentFirstLaunchXrayBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.TooManyMethods",
        "PMD.CyclomaticComplexity",
        "PMD.ImplicitFunctionalInterface",
        "PMD.ShortClassName",
        "PMD.ShortMethodName",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class FirstLaunchXrayFragment extends Fragment {

    public interface Host {
        void onXraySettingsCompleted();
    }

    @Nullable
    private FragmentFirstLaunchXrayBinding binding;

    private AppCompatCheckBox allowLanCheckBox;
    private AppCompatCheckBox allowInsecureCheckBox;
    private AppCompatCheckBox ipv6CheckBox;
    private AppCompatCheckBox sniffingCheckBox;

    private final ActivityResultLauncher<Intent> qrScanLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> handleQrScanResult(result.getResultCode(), result.getData())
    );

    public static FirstLaunchXrayFragment create() {
        return new FirstLaunchXrayFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchXrayBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        buildForm();
        bindButtons();
        loadSettings(XrayStore.getXraySettings(requireContext()));
    }

    @Override
    public void onDestroyView() {
        allowLanCheckBox = null;
        allowInsecureCheckBox = null;
        ipv6CheckBox = null;
        sniffingCheckBox = null;
        binding = null;
        super.onDestroyView();
    }

    private void buildForm() {
        if (binding == null) {
            return;
        }
        LinearLayout container = binding.containerXrayFields;
        container.removeAllViews();
        allowLanCheckBox = addCheckBox(container, R.string.xray_settings_allow_lan_title);
        allowInsecureCheckBox = addCheckBox(container, R.string.xray_settings_allow_insecure_title);
        ipv6CheckBox = addCheckBox(container, R.string.xray_settings_ipv6_title);
        sniffingCheckBox = addCheckBox(container, R.string.xray_settings_sniffing_title);
    }

    private void bindButtons() {
        if (binding == null) {
            return;
        }
        binding.buttonFirstLaunchPasteClipboard.setOnClickListener(view -> {
            Haptics.softSelection(view);
            pasteFromClipboard();
        });
        binding.buttonFirstLaunchScanQr.setOnClickListener(view -> {
            Haptics.softSelection(view);
            launchQrScanner();
        });
        binding.buttonContinueXray.setOnClickListener(view -> {
            Haptics.softConfirm(view);
            saveAndContinue();
        });
    }

    private void launchQrScanner() {
        Intent intent = QrScanActivity.Companion.createIntent(requireContext(), getString(R.string.qr_scan_title));
        try {
            qrScanLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException ignored) {
            Toast.makeText(requireContext(), R.string.qr_scan_camera_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void handleQrScanResult(int resultCode, @Nullable Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            return;
        }
        String scanned = data.getStringExtra(QrScanActivity.EXTRA_QR_SCANNER_RESULT);
        if (TextUtils.isEmpty(scanned)) {
            return;
        }
        applyImportedText(scanned.trim());
    }

    private void pasteFromClipboard() {
        Context context = requireContext();
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence raw = clipData.getItemAt(0).coerceToText(context);
        String text = raw != null ? raw.toString().trim() : "";
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        applyImportedText(text);
    }

    private void applyImportedText(@NonNull String rawText) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        WingsImportParser.ImportedConfig parsed;
        try {
            parsed = WingsImportParser.parseFromText(rawText);
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.clipboard_import_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (wings.v.core.GuardianImportGate.needsConfirmation(parsed)) {
            pendingImportRawText = rawText;
            pendingImport = parsed;
            if (!android.text.TextUtils.isEmpty(parsed.guardianAdminUsername)) {
                wings.v.core.GuardianImportPrompt.show(requireActivity(), parsed, () ->
                    guardianConfirmLauncher.launch(wings.v.core.GuardianImportGate.createIntent(requireActivity()))
                );
            } else {
                guardianConfirmLauncher.launch(wings.v.core.GuardianImportGate.createIntent(requireActivity()));
            }
            return;
        }
        applyParsedImport(rawText, parsed);
    }

    private AppCompatCheckBox addCheckBox(LinearLayout container, int labelRes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setBackgroundResource(R.drawable.bg_first_launch_permission_row);
        row.setPadding(0, 0, dp(18), 0);

        AppCompatCheckBox checkBox = new AppCompatCheckBox(requireContext());
        checkBox.setGravity(Gravity.CENTER_VERTICAL);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xEAF9FBFF));
        checkBox.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
            dp(48),
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        checkParams.setMarginStart(dp(22));
        row.addView(checkBox, checkParams);

        TextView label = new TextView(requireContext());
        label.setText(labelRes);
        label.setTextColor(0xF4FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.samsungone));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        );
        labelParams.setMarginStart(dp(8));
        row.addView(label, labelParams);

        row.setOnClickListener(view -> {
            Haptics.softSliderStep(view);
            checkBox.setChecked(!checkBox.isChecked());
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, container.getChildCount() == 0 ? 0 : dp(10), 0, 0);
        container.addView(row, params);
        return checkBox;
    }

    private void loadSettings(XraySettings settings) {
        XraySettings value = settings != null ? settings : new XraySettings();
        if (allowLanCheckBox != null) {
            allowLanCheckBox.setChecked(value.allowLan);
        }
        if (allowInsecureCheckBox != null) {
            allowInsecureCheckBox.setChecked(value.allowInsecure);
        }
        if (ipv6CheckBox != null) {
            ipv6CheckBox.setChecked(value.ipv6);
        }
        if (sniffingCheckBox != null) {
            sniffingCheckBox.setChecked(value.sniffingEnabled);
        }
    }

    private final ActivityResultLauncher<Intent> guardianConfirmLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> onGuardianConfirmResult(result.getResultCode())
    );
    private String pendingImportRawText;
    private WingsImportParser.ImportedConfig pendingImport;

    private void onGuardianConfirmResult(int resultCode) {
        String text = pendingImportRawText;
        WingsImportParser.ImportedConfig parsed = pendingImport;
        pendingImportRawText = null;
        pendingImport = null;
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (resultCode == android.app.Activity.RESULT_OK && parsed != null && text != null) {
            applyParsedImport(text, parsed);
        } else {
            Toast.makeText(context, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyParsedImport(String rawText, WingsImportParser.ImportedConfig importedConfig) {
        Context context = requireContext();
        AppPrefs.applyImportedConfig(context, importedConfig);
        requestReconnectAfterImport(context, rawText);
        loadSettings(XrayStore.getXraySettings(context));
        Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onXraySettingsCompleted();
        }
    }

    private void requestReconnectAfterImport(Context context, @Nullable String importedText) {
        if (!ProxyTunnelService.isActive()) {
            return;
        }
        String normalized = importedText == null ? "" : importedText.trim().toLowerCase(Locale.ROOT);
        String reason = normalized.startsWith("vless://")
            ? "Imported vless configuration applied"
            : normalized.startsWith("http://") || normalized.startsWith("https://")
                ? "Imported xray subscription applied"
                : "Imported wingsv configuration applied";
        ProxyTunnelService.requestReconnect(context, reason);
    }

    private void saveAndContinue() {
        XraySettings settings = XrayStore.getXraySettings(requireContext()).copy();
        settings.allowLan = allowLanCheckBox != null && allowLanCheckBox.isChecked();
        settings.allowInsecure = allowInsecureCheckBox != null && allowInsecureCheckBox.isChecked();
        settings.ipv6 = ipv6CheckBox == null || ipv6CheckBox.isChecked();
        settings.sniffingEnabled = sniffingCheckBox == null || sniffingCheckBox.isChecked();
        XrayStore.setXraySettings(requireContext(), settings);
        XrayStore.setBackendType(requireContext(), BackendType.XRAY);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onXraySettingsCompleted();
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
