package wings.v;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
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
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.Fragment;
import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.ProxySettings;
import wings.v.core.WingsImportParser;
import wings.v.databinding.FragmentFirstLaunchVkTurnBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class FirstLaunchVkTurnFragment extends Fragment {

    private static final int IPV4_PART_COUNT = 4;
    private static final int IPV4_PART_MAX = 255;

    @FunctionalInterface
    public interface Host {
        void onVkTurnSettingsCompleted();
    }

    @Nullable
    private FragmentFirstLaunchVkTurnBinding binding;

    private final List<InputField> inputFields = new ArrayList<>();
    private AppCompatCheckBox useUdpCheckBox;
    private AppCompatCheckBox noObfuscationCheckBox;
    private AppCompatCheckBox manualCaptchaCheckBox;
    private boolean applyingValues;
    private boolean validationAttempted;

    public static FirstLaunchVkTurnFragment create() {
        return new FirstLaunchVkTurnFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchVkTurnBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        buildForm();
        bindButtons();
        loadSettings(AppPrefs.getSettings(requireContext()));
        updateImportButtonStyle();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateImportButtonStyle();
    }

    @Override
    public void onDestroyView() {
        inputFields.clear();
        useUdpCheckBox = null;
        noObfuscationCheckBox = null;
        manualCaptchaCheckBox = null;
        binding = null;
        super.onDestroyView();
    }

    private void buildForm() {
        if (binding == null) {
            return;
        }
        LinearLayout container = binding.containerVkTurnFields;
        container.removeAllViews();
        inputFields.clear();

        addInput(container, AppPrefs.KEY_ENDPOINT, R.string.first_launch_vk_turn_endpoint, true, false);
        addInput(container, AppPrefs.KEY_VK_LINK, R.string.first_launch_vk_turn_vk_link, true, true);
        addInput(container, AppPrefs.KEY_THREADS, R.string.first_launch_vk_turn_threads, true, false);

        useUdpCheckBox = addCheckBox(container, R.string.first_launch_vk_turn_use_udp);
        noObfuscationCheckBox = addCheckBox(container, R.string.first_launch_vk_turn_no_obfuscation);
        manualCaptchaCheckBox = addCheckBox(container, R.string.manual_captcha_title);
        addSectionLabel(container, R.string.first_launch_vk_turn_wireguard_interface);
        addInput(container, AppPrefs.KEY_WG_PRIVATE_KEY, R.string.first_launch_vk_turn_wg_private_key, true, false);
        addInput(container, AppPrefs.KEY_WG_ADDRESSES, R.string.first_launch_vk_turn_wg_addresses, true, false);
        addInput(container, AppPrefs.KEY_WG_DNS, R.string.first_launch_vk_turn_wg_dns, false, false);
        addInput(container, AppPrefs.KEY_WG_MTU, R.string.first_launch_vk_turn_wg_mtu, true, false);

        addSectionLabel(container, R.string.first_launch_vk_turn_wireguard_peer);
        addInput(container, AppPrefs.KEY_WG_PUBLIC_KEY, R.string.first_launch_vk_turn_wg_public_key, true, false);
        addInput(
            container,
            AppPrefs.KEY_WG_PRESHARED_KEY,
            R.string.first_launch_vk_turn_wg_preshared_key,
            false,
            false
        );
        addInput(container, AppPrefs.KEY_WG_ALLOWED_IPS, R.string.first_launch_vk_turn_wg_allowed_ips, true, false);
    }

    private void bindButtons() {
        if (binding == null) {
            return;
        }
        binding.buttonImportWingsv.setOnClickListener(view -> {
            Haptics.softSelection(view);
            importWingsvFromClipboard();
        });
        binding.buttonContinueVkTurn.setOnClickListener(view -> {
            Haptics.softConfirm(view);
            saveAndContinue();
        });
    }

    private void updateImportButtonStyle() {
        if (binding == null) {
            return;
        }
        boolean primaryStyle = shouldUsePrimaryImportButtonStyle();
        binding.buttonImportWingsv.setBackgroundResource(
            primaryStyle ? R.drawable.bg_first_launch_primary_button : R.drawable.bg_first_launch_inline_button
        );
        binding.buttonImportWingsv.setTextColor(primaryStyle ? 0xFF010102 : 0xFF0B2239);
    }

    private boolean shouldUsePrimaryImportButtonStyle() {
        try {
            Context context = requireContext();
            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboardManager == null) {
                return true;
            }
            if (!clipboardManager.hasPrimaryClip()) {
                return false;
            }
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return false;
            }
            for (int i = 0; i < clipData.getItemCount(); i++) {
                CharSequence rawText = clipData.getItemAt(i).coerceToText(context);
                if (rawText != null && rawText.toString().contains("wingsv://")) {
                    return true;
                }
            }
            return false;
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private InputField addInput(LinearLayout container, String key, int labelRes, boolean required, boolean multiline) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_first_launch_permission_row);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        TextView label = new TextView(requireContext());
        label.setText(labelRes);
        label.setTextColor(0xF7FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.samsungone));
        label.setIncludeFontPadding(false);
        row.addView(
            label,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        AppCompatEditText editText = new AppCompatEditText(requireContext());
        editText.setSingleLine(!multiline);
        editText.setMaxLines(multiline ? 4 : 1);
        editText.setMinLines(multiline ? 2 : 1);
        editText.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
        if (isNumericField(key)) {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
            editText.setFilters(
                new InputFilter[] { new InputFilter.LengthFilter(AppPrefs.KEY_THREADS.equals(key) ? 3 : 5) }
            );
        } else {
            editText.setInputType(
                InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                    (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)
            );
        }
        editText.setTextColor(0xFFFFFFFF);
        editText.setHintTextColor(0x99FFFFFF);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        editText.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.samsungone));
        editText.setBackgroundColor(0x00000000);
        editText.setPadding(0, dp(9), 0, 0);
        row.addView(
            editText,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, inputFields.isEmpty() ? 0 : dp(10), 0, 0);
        container.addView(row, params);

        InputField field = new InputField(key, editText, required);
        inputFields.add(field);
        editText.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable value) {
                    if (applyingValues) {
                        return;
                    }
                    validateField(field);
                    if (validationAttempted) {
                        validateSettings(collectSettings(), false);
                    }
                }
            }
        );
        editText.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                scrollFieldIntoView(view);
            }
        });
        editText.setOnClickListener(this::scrollFieldIntoView);
        return field;
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
        params.setMargins(0, dp(10), 0, 0);
        container.addView(row, params);
        return checkBox;
    }

    private void addSectionLabel(LinearLayout container, int labelRes) {
        TextView label = new TextView(requireContext());
        label.setText(labelRes);
        label.setTextColor(0xDFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.samsungone));
        label.setIncludeFontPadding(false);
        label.setPadding(dp(4), dp(18), dp(4), dp(8));
        container.addView(
            label,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );
    }

    private void loadSettings(ProxySettings settings) {
        applyingValues = true;
        setText(AppPrefs.KEY_ENDPOINT, settings.endpoint);
        setText(AppPrefs.KEY_VK_LINK, settings.vkLink);
        setText(AppPrefs.KEY_THREADS, String.valueOf(settings.threads > 0 ? settings.threads : 8));
        if (useUdpCheckBox != null) {
            useUdpCheckBox.setChecked(settings.useUdp);
        }
        if (noObfuscationCheckBox != null) {
            noObfuscationCheckBox.setChecked(settings.noObfuscation);
        }
        if (manualCaptchaCheckBox != null) {
            manualCaptchaCheckBox.setChecked(settings.manualCaptcha);
        }
        setText(AppPrefs.KEY_WG_PRIVATE_KEY, settings.wgPrivateKey);
        setText(AppPrefs.KEY_WG_ADDRESSES, settings.wgAddresses);
        setText(AppPrefs.KEY_WG_DNS, TextUtils.isEmpty(settings.wgDns) ? "1.1.1.1, 1.0.0.1" : settings.wgDns);
        setText(AppPrefs.KEY_WG_MTU, String.valueOf(settings.wgMtu > 0 ? settings.wgMtu : 1280));
        setText(AppPrefs.KEY_WG_PUBLIC_KEY, settings.wgPublicKey);
        setText(AppPrefs.KEY_WG_PRESHARED_KEY, settings.wgPresharedKey);
        setText(
            AppPrefs.KEY_WG_ALLOWED_IPS,
            TextUtils.isEmpty(settings.wgAllowedIps) ? "0.0.0.0/0, ::/0" : settings.wgAllowedIps
        );
        applyingValues = false;
        clearErrors();
    }

    private void saveAndContinue() {
        ProxySettings settings = collectSettings();
        validationAttempted = true;
        String error = validateSettings(settings, true);
        if (!TextUtils.isEmpty(error)) {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
            return;
        }
        AppPrefs.applyVkTurnSettings(requireContext(), settings);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onVkTurnSettingsCompleted();
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void importWingsvFromClipboard() {
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
        if (!text.contains("wingsv://")) {
            Toast.makeText(context, R.string.first_launch_vk_turn_import_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            WingsImportParser.ImportedConfig importedConfig = WingsImportParser.parseFromText(text);
            if (importedConfig.backendType != BackendType.VK_TURN_WIREGUARD) {
                Toast.makeText(context, R.string.first_launch_vk_turn_import_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            AppPrefs.applyImportedConfig(context, importedConfig);
            requestReconnectAfterImport(context, text);
            loadSettings(AppPrefs.getSettings(context));
            validationAttempted = true;
            validateSettings(collectSettings(), false);
            updateImportButtonStyle();
            Toast.makeText(context, R.string.clipboard_import_success, Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onVkTurnSettingsCompleted();
            }
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.first_launch_vk_turn_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestReconnectAfterImport(Context context, @Nullable String importedText) {
        if (!ProxyTunnelService.isActive()) {
            return;
        }
        String normalized = importedText == null ? "" : importedText.trim().toLowerCase(Locale.ROOT);
        String reason = normalized.startsWith("vless://")
            ? "Imported vless configuration applied"
            : "Imported wingsv configuration applied";
        ProxyTunnelService.requestReconnect(context, reason);
    }

    private ProxySettings collectSettings() {
        ProxySettings settings = new ProxySettings();
        settings.backendType = BackendType.VK_TURN_WIREGUARD;
        settings.endpoint = text(AppPrefs.KEY_ENDPOINT);
        settings.vkLink = text(AppPrefs.KEY_VK_LINK);
        settings.threads = parsePositiveInt(text(AppPrefs.KEY_THREADS), 8);
        settings.useUdp = useUdpCheckBox == null || useUdpCheckBox.isChecked();
        settings.noObfuscation = noObfuscationCheckBox != null && noObfuscationCheckBox.isChecked();
        settings.manualCaptcha = manualCaptchaCheckBox != null && manualCaptchaCheckBox.isChecked();
        settings.captchaAutoSolver = AppPrefs.getSettings(requireContext()).captchaAutoSolver;
        settings.turnSessionMode = "auto";
        settings.localEndpoint = "127.0.0.1:9000";
        settings.turnHost = "";
        settings.turnPort = "";
        settings.wgPrivateKey = text(AppPrefs.KEY_WG_PRIVATE_KEY);
        settings.wgAddresses = text(AppPrefs.KEY_WG_ADDRESSES);
        settings.wgDns = text(AppPrefs.KEY_WG_DNS);
        settings.wgMtu = parsePositiveInt(text(AppPrefs.KEY_WG_MTU), 1280);
        settings.wgPublicKey = text(AppPrefs.KEY_WG_PUBLIC_KEY);
        settings.wgPresharedKey = text(AppPrefs.KEY_WG_PRESHARED_KEY);
        settings.wgAllowedIps = text(AppPrefs.KEY_WG_ALLOWED_IPS);
        return settings;
    }

    private String validateSettings(ProxySettings settings, boolean requestFocusOnError) {
        clearErrors();
        boolean hasError = false;
        for (InputField field : inputFields) {
            if (field.required && TextUtils.isEmpty(text(field.key))) {
                field.editText.setError(getString(R.string.first_launch_vk_turn_field_required));
                if (requestFocusOnError && !hasError) {
                    field.editText.requestFocus();
                }
                hasError = true;
            }
        }
        if (!isPositiveInt(text(AppPrefs.KEY_THREADS))) {
            markInvalid(AppPrefs.KEY_THREADS, R.string.first_launch_vk_turn_field_number_error, requestFocusOnError);
            hasError = true;
        }
        if (!TextUtils.isEmpty(text(AppPrefs.KEY_ENDPOINT)) && !isValidEndpoint(text(AppPrefs.KEY_ENDPOINT))) {
            markInvalid(AppPrefs.KEY_ENDPOINT, R.string.first_launch_vk_turn_endpoint_error, requestFocusOnError);
            hasError = true;
        }
        if (!isPositiveInt(text(AppPrefs.KEY_WG_MTU))) {
            markInvalid(AppPrefs.KEY_WG_MTU, R.string.first_launch_vk_turn_field_number_error, requestFocusOnError);
            hasError = true;
        }
        if (hasError) {
            return getString(R.string.first_launch_vk_turn_validation_error);
        }
        String configError = settings.validate();
        return TextUtils.isEmpty(configError) ? "" : configError;
    }

    private boolean validateField(InputField field) {
        if (field.required && TextUtils.isEmpty(text(field.key))) {
            field.editText.setError(getString(R.string.first_launch_vk_turn_field_required));
            return false;
        }
        if (
            (AppPrefs.KEY_THREADS.equals(field.key) || AppPrefs.KEY_WG_MTU.equals(field.key)) &&
            !isPositiveInt(text(field.key))
        ) {
            field.editText.setError(getString(R.string.first_launch_vk_turn_field_number_error));
            return false;
        }
        if (AppPrefs.KEY_ENDPOINT.equals(field.key) && !isValidEndpoint(text(field.key))) {
            field.editText.setError(getString(R.string.first_launch_vk_turn_endpoint_error));
            return false;
        }
        field.editText.setError(null);
        return true;
    }

    private void markInvalid(String key, int errorRes, boolean requestFocus) {
        InputField field = findField(key);
        if (field == null) {
            return;
        }
        field.editText.setError(getString(errorRes));
        if (requestFocus) {
            field.editText.requestFocus();
            scrollFieldIntoView(field.editText);
        }
    }

    private void scrollFieldIntoView(@NonNull View view) {
        if (binding == null) {
            return;
        }
        binding.scrollVkTurnFields.post(() -> {
            if (binding == null) {
                return;
            }
            Rect rect = new Rect();
            view.getDrawingRect(rect);
            binding.scrollVkTurnFields.offsetDescendantRectToMyCoords(view, rect);
            int extraBottomSpace = dp(24);
            rect.bottom += extraBottomSpace;
            rect.top = Math.max(0, rect.top - dp(12));
            binding.scrollVkTurnFields.requestChildRectangleOnScreen(view, rect, true);
        });
    }

    private void clearErrors() {
        for (InputField field : inputFields) {
            field.editText.setError(null);
        }
    }

    private void setText(String key, String value) {
        InputField field = findField(key);
        if (field != null) {
            field.editText.setText(value == null ? "" : value);
        }
    }

    private String text(String key) {
        InputField field = findField(key);
        if (field == null || field.editText.getText() == null) {
            return "";
        }
        return field.editText.getText().toString().trim();
    }

    @Nullable
    private InputField findField(String key) {
        for (InputField field : inputFields) {
            if (field.key.equals(key)) {
                return field;
            }
        }
        return null;
    }

    private boolean isPositiveInt(String rawValue) {
        return parsePositiveInt(rawValue, -1) > 0;
    }

    private boolean isNumericField(String key) {
        return AppPrefs.KEY_THREADS.equals(key) || AppPrefs.KEY_WG_MTU.equals(key);
    }

    private boolean isValidEndpoint(String rawValue) {
        HostPort hostPort = parseHostPort(rawValue);
        return hostPort != null && isValidHost(hostPort.host) && isValidPort(hostPort.port);
    }

    @Nullable
    private HostPort parseHostPort(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[")) {
            int closeIndex = value.indexOf(']');
            if (closeIndex <= 1 || closeIndex + 2 >= value.length() || value.charAt(closeIndex + 1) != ':') {
                return null;
            }
            return new HostPort(value.substring(1, closeIndex), value.substring(closeIndex + 2));
        }
        int colonIndex = value.lastIndexOf(':');
        if (colonIndex <= 0 || colonIndex == value.length() - 1 || value.indexOf(':') != colonIndex) {
            return null;
        }
        return new HostPort(value.substring(0, colonIndex), value.substring(colonIndex + 1));
    }

    private boolean isValidPort(String rawPort) {
        try {
            int port = Integer.parseInt(rawPort.trim());
            return port > 0 && port <= 65535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isValidHost(String rawHost) {
        if (TextUtils.isEmpty(rawHost) || rawHost.contains("/") || rawHost.contains("://")) {
            return false;
        }
        String host = rawHost.trim();
        if (host.contains(":")) {
            return host.length() >= 2;
        }
        if (isValidIpv4(host)) {
            return true;
        }
        try {
            String asciiHost = IDN.toASCII(host);
            if (asciiHost.isEmpty() || asciiHost.length() > 253) {
                return false;
            }
            String[] labels = asciiHost.split("\\.");
            for (String label : labels) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    return false;
                }
                for (int i = 0; i < label.length(); i++) {
                    char ch = label.charAt(i);
                    boolean valid =
                        (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-';
                    if (!valid) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean isValidIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != IPV4_PART_COUNT) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < '0' || ch > '9') {
                    return false;
                }
            }
            int value = Integer.parseInt(part);
            if (value > IPV4_PART_MAX) {
                return false;
            }
        }
        return true;
    }

    private int parsePositiveInt(String rawValue, int fallback) {
        try {
            int value = Integer.parseInt(rawValue.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private static final class InputField {

        final String key;
        final AppCompatEditText editText;
        final boolean required;

        InputField(String key, AppCompatEditText editText, boolean required) {
            this.key = key;
            this.editText = editText;
            this.required = required;
        }
    }

    private static final class HostPort {

        final String host;
        final String port;

        HostPort(String host, String port) {
            this.host = host;
            this.port = port;
        }
    }
}
