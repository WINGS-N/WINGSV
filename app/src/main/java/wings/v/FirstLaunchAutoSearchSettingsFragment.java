package wings.v;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import wings.v.core.AutoSearchManager;
import wings.v.core.DisplayDensityUtils;
import wings.v.core.Haptics;
import wings.v.databinding.FragmentFirstLaunchAutoSearchSettingsBinding;

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
        "PMD.AtLeastOneConstructor",
        "PMD.ImplicitFunctionalInterface",
        "PMD.ShortClassName",
        "PMD.UncommentedEmptyMethodBody",
    }
)
public class FirstLaunchAutoSearchSettingsFragment extends Fragment {

    public interface Host {
        void onAutoSearchSettingsCompleted();
    }

    @Nullable
    private FragmentFirstLaunchAutoSearchSettingsBinding binding;

    private final List<NumberField> fields = new ArrayList<>();

    public static FirstLaunchAutoSearchSettingsFragment create() {
        return new FirstLaunchAutoSearchSettingsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchAutoSearchSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        buildForm();
        binding.buttonAutoSearchSettingsNext.setOnClickListener(button -> {
            Haptics.softConfirm(button);
            if (!saveSettings()) {
                return;
            }
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onAutoSearchSettingsCompleted();
            }
        });
    }

    @Override
    public void onDestroyView() {
        fields.clear();
        binding = null;
        super.onDestroyView();
    }

    private void buildForm() {
        if (binding == null) {
            return;
        }
        LinearLayout container = binding.containerAutoSearchSettingsFields;
        container.removeAllViews();
        fields.clear();
        addNumber(
            container,
            R.string.auto_search_setting_target_count,
            AutoSearchManager.getTargetProfileCount(requireContext()),
            1,
            20
        );
        addNumber(
            container,
            R.string.auto_search_setting_tcping_timeout,
            AutoSearchManager.getTcpingTimeoutMs(requireContext()),
            300,
            10_000
        );
        addNumber(
            container,
            R.string.auto_search_setting_download_size,
            AutoSearchManager.getDownloadSizeMb(requireContext()),
            1,
            100
        );
        addNumber(
            container,
            R.string.auto_search_setting_download_timeout,
            AutoSearchManager.getDownloadTimeoutSeconds(requireContext()),
            3,
            120
        );
        addNumber(
            container,
            R.string.auto_search_setting_download_attempts,
            AutoSearchManager.getDownloadAttempts(requireContext()),
            1,
            10
        );
    }

    private void addNumber(LinearLayout container, int labelRes, int value, int min, int max) {
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
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        editText.setFilters(new InputFilter[] { new InputFilter.LengthFilter(5) });
        editText.setText(String.valueOf(value));
        editText.setSelectAllOnFocus(true);
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

        NumberField field = new NumberField(editText, min, max);
        fields.add(field);
        editText.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable value) {
                    validate(field);
                }
            }
        );

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, container.getChildCount() == 0 ? 0 : dp(10), 0, 0);
        container.addView(row, params);
    }

    private boolean validate(NumberField field) {
        int value = field.value();
        boolean valid = value >= field.min && value <= field.max;
        field.editText.setError(
            valid ? null : getString(R.string.first_launch_auto_search_number_error, field.min, field.max)
        );
        return valid;
    }

    private boolean saveSettings() {
        boolean valid = true;
        for (NumberField field : fields) {
            valid = validate(field) && valid;
        }
        if (!valid || fields.size() < 5) {
            return false;
        }
        AutoSearchManager.setTargetProfileCount(requireContext(), fields.get(0).clampedValue());
        AutoSearchManager.setTcpingTimeoutMs(requireContext(), fields.get(1).clampedValue());
        AutoSearchManager.setDownloadSizeMb(requireContext(), fields.get(2).clampedValue());
        AutoSearchManager.setDownloadTimeoutSeconds(requireContext(), fields.get(3).clampedValue());
        AutoSearchManager.setDownloadAttempts(requireContext(), fields.get(4).clampedValue());
        return true;
    }

    private int dp(int value) {
        return DisplayDensityUtils.dpToPx(requireContext(), value);
    }

    private static final class NumberField {

        final AppCompatEditText editText;
        final int min;
        final int max;

        NumberField(AppCompatEditText editText, int min, int max) {
            this.editText = editText;
            this.min = min;
            this.max = max;
        }

        int value() {
            try {
                return Integer.parseInt(editText.getText() == null ? "" : editText.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }

        int clampedValue() {
            return Math.max(min, Math.min(max, value()));
        }
    }
}
