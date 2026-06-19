package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import dev.oneuiproject.oneui.qr.app.QrScanActivity;
import wings.v.core.AppPrefs;
import wings.v.core.GuardianImportGate;
import wings.v.core.Haptics;
import wings.v.core.WingsImportParser;
import wings.v.databinding.FragmentFirstLaunchConnectionBinding;

@SuppressWarnings({ "PMD.NullAssignment", "PMD.AvoidCatchingGenericException" })
public class FirstLaunchConnectionFragment extends Fragment {

    public static final String CHOICE_VK_TURN = "vk_turn";
    public static final String CHOICE_XRAY = "xray";
    public static final String CHOICE_AUTO_SEARCH = "auto_search";

    private static final int REQUEST_GUARDIAN_CONFIRM = 4304;

    @Nullable
    private FragmentFirstLaunchConnectionBinding binding;

    @Nullable
    private String pendingImportRawText;

    @Nullable
    private WingsImportParser.ImportedConfig pendingImportParsed;

    private final ActivityResultLauncher<Intent> qrScanLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> handleQrScanResult(result.getResultCode(), result.getData())
    );

    public interface Host {
        void onConnectionChoiceSelected(@NonNull String choice);

        void onConnectionChoiceSkipped();

        /** Called after the user successfully imported a config from clipboard or QR. */
        void onConnectionImportApplied();
    }

    public static FirstLaunchConnectionFragment create() {
        return new FirstLaunchConnectionFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchConnectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.buttonFirstLaunchVkTurn.setOnClickListener(v -> dispatchChoice(v, CHOICE_VK_TURN));
        binding.buttonFirstLaunchXray.setOnClickListener(v -> dispatchChoice(v, CHOICE_XRAY));
        binding.buttonFirstLaunchAutoSearch.setOnClickListener(v -> dispatchChoice(v, CHOICE_AUTO_SEARCH));
        binding.textFirstLaunchSkip.setOnClickListener(this::dispatchSkip);
        binding.buttonFirstLaunchPasteClipboard.setOnClickListener(this::onPasteClipboardClicked);
        binding.buttonFirstLaunchScanQr.setOnClickListener(this::onScanQrClicked);
    }

    private void dispatchChoice(View view, String choice) {
        Haptics.softConfirm(view);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onConnectionChoiceSelected(choice);
        }
    }

    private void dispatchSkip(View view) {
        Haptics.softSelection(view);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onConnectionChoiceSkipped();
        }
    }

    private void onPasteClipboardClicked(View view) {
        Haptics.softSelection(view);
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
        CharSequence text = clipData.getItemAt(0).coerceToText(context);
        String rawText = text != null ? text.toString() : null;
        if (TextUtils.isEmpty(rawText)) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        applyImportedText(rawText);
    }

    private void onScanQrClicked(View view) {
        Haptics.softSelection(view);
        // Camera permission is requested by QrScanActivity itself only after
        // the user taps this — never on app startup.
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
        if (GuardianImportGate.needsConfirmation(parsed)) {
            pendingImportRawText = rawText;
            pendingImportParsed = parsed;
            if (!android.text.TextUtils.isEmpty(parsed.guardianAdminUsername)) {
                wings.v.core.GuardianImportPrompt.show(requireActivity(), parsed, () ->
                    GuardianImportGate.launchFromFragment(this, REQUEST_GUARDIAN_CONFIRM)
                );
            } else {
                GuardianImportGate.launchFromFragment(this, REQUEST_GUARDIAN_CONFIRM);
            }
            return;
        }
        commitImportedConfig(context, parsed);
    }

    private void commitImportedConfig(@NonNull Context context, @NonNull WingsImportParser.ImportedConfig parsed) {
        AppPrefs.applyImportedConfig(context, parsed);
        Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onConnectionImportApplied();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GUARDIAN_CONFIRM) {
            return;
        }
        WingsImportParser.ImportedConfig parsed = pendingImportParsed;
        pendingImportRawText = null;
        pendingImportParsed = null;
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (resultCode == android.app.Activity.RESULT_OK && parsed != null) {
            commitImportedConfig(context, parsed);
        } else {
            Toast.makeText(context, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
