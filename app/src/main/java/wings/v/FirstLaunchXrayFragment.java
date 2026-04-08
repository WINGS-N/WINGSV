package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.fragment.app.Fragment;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.WingsImportParser;
import wings.v.core.XraySettings;
import wings.v.core.XrayStore;
import wings.v.databinding.FragmentFirstLaunchXrayBinding;

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
        binding.buttonImportWingsv.setOnClickListener(view -> {
            Haptics.softSelection(view);
            importFromClipboard("wingsv://", R.string.first_launch_xray_import_wingsv_invalid);
        });
        binding.buttonImportVless.setOnClickListener(view -> {
            Haptics.softSelection(view);
            importFromClipboard("vless://", R.string.first_launch_xray_import_vless_invalid);
        });
        binding.buttonContinueXray.setOnClickListener(view -> {
            Haptics.softConfirm(view);
            saveAndContinue();
        });
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

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void importFromClipboard(String requiredScheme, int invalidMessageRes) {
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
        CharSequence rawText = clipData.getItemAt(0).coerceToText(context);
        String text = rawText != null ? rawText.toString() : "";
        if (!text.contains(requiredScheme)) {
            Toast.makeText(context, invalidMessageRes, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            WingsImportParser.ImportedConfig importedConfig = WingsImportParser.parseFromText(text);
            if (importedConfig.backendType != BackendType.XRAY) {
                Toast.makeText(context, invalidMessageRes, Toast.LENGTH_SHORT).show();
                return;
            }
            AppPrefs.applyImportedConfig(context, importedConfig);
            loadSettings(XrayStore.getXraySettings(context));
            Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onXraySettingsCompleted();
            }
        } catch (Exception ignored) {
            Toast.makeText(context, invalidMessageRes, Toast.LENGTH_SHORT).show();
        }
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
