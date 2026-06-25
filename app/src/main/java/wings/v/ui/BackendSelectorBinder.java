package wings.v.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Toast;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SeslArrayAdapter;
import androidx.fragment.app.Fragment;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wings.v.ExternalActions;
import wings.v.R;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.TunnelMode;
import wings.v.core.XrayStore;

/**
 * Shared settings-style backend dropdown (top-level + VK TURN sub-backend) bound
 * to a CardItemView row + hidden AppCompatSpinner pair, mirroring the settings
 * screen's DropDownPreference (OneUI spinner with a checkmark on the active
 * entry). Used by both profile screens so the selector is identical wherever it
 * appears: BackendProfilesFragment for WG/AWG/VK TURN, and the embedded Xray
 * ProfilesFragment (where it sits as the first row of the Actions card).
 */
final class BackendSelectorBinder {

    @FunctionalInterface
    interface OnBackendChanged {
        void onChanged();
    }

    private BackendSelectorBinder() {}

    @SuppressWarnings("PMD.LawOfDemeter")
    static void bind(
        Fragment fragment,
        CardItemView topRow,
        AppCompatSpinner topSpinner,
        CardItemView subRow,
        AppCompatSpinner subSpinner,
        OnBackendChanged onBackendChanged
    ) {
        Context context = fragment.requireContext();
        topRow.setTitle(context.getString(R.string.backend_profiles_selector_title));
        subRow.setTitle(context.getString(R.string.sub_backend_title));
        bindDropdown(
            fragment,
            topRow,
            topSpinner,
            R.array.backend_top_entries,
            R.array.backend_top_values,
            () -> XrayStore.getBackendType(fragment.requireContext()).topLevelGroup(),
            value -> onTopLevelChosen(fragment, value, onBackendChanged)
        );
        bindDropdown(
            fragment,
            subRow,
            subSpinner,
            R.array.tunnel_mode_entries,
            R.array.tunnel_mode_values,
            () -> AppPrefs.getVkTurnTunnelMode(fragment.requireContext()).prefValue,
            value -> onSubBackendChosen(fragment, value, onBackendChanged)
        );
    }

    static void refresh(Context context, CardItemView topRow, CardItemView subRow) {
        BackendType backendType = XrayStore.getBackendType(context);
        topRow.setSummary(topLevelTitle(context, backendType));
        boolean vkTurn = "vk_turn".equals(backendType.topLevelGroup());
        subRow.setVisibility(vkTurn ? View.VISIBLE : View.GONE);
        if (vkTurn) {
            subRow.setSummary(subBackendTitle(context, backendType));
        }
    }

    @SuppressWarnings({ "PMD.LawOfDemeter", "PMD.CognitiveComplexity" })
    private static void bindDropdown(
        Fragment fragment,
        CardItemView row,
        AppCompatSpinner spinner,
        int entriesRes,
        int valuesRes,
        Supplier<String> getter,
        Consumer<String> setter
    ) {
        Context context = fragment.requireContext();
        CharSequence[] entries = context.getResources().getTextArray(entriesRes);
        String[] values = context.getResources().getStringArray(valuesRes);
        SeslArrayAdapter adapter = new SeslArrayAdapter(
            context,
            androidx.appcompat.R.layout.support_simple_spinner_dropdown_item
        );
        for (CharSequence entry : entries) {
            adapter.add(entry.toString());
        }
        spinner.setAdapter(adapter);
        spinner.setSoundEffectsEnabled(false);
        spinner.setSelection(indexOf(values, getter.get()));
        spinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= values.length) {
                        return;
                    }
                    String newValue = values[position];
                    if (TextUtils.equals(newValue, getter.get())) {
                        return;
                    }
                    Haptics.softSelection(parent);
                    setter.accept(newValue);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // noop
                }
            }
        );
        row.setOnClickListener(view -> {
            Haptics.softSelection(view);
            spinner.setSelection(indexOf(values, getter.get()));
            spinner.performClick();
        });
    }

    private static void onTopLevelChosen(Fragment fragment, String topLevel, OnBackendChanged onBackendChanged) {
        Context context = fragment.requireContext();
        if ("wb_stream".equals(topLevel)) {
            Toast.makeText(context, R.string.backend_top_wb_stream_unavailable_toast, Toast.LENGTH_SHORT).show();
            onBackendChanged.onChanged();
            return;
        }
        if ("vk_turn".equals(topLevel)) {
            applyBackend(
                context,
                BackendType.fromTopLevelAndSub("vk_turn", AppPrefs.getVkTurnTunnelMode(context)),
                onBackendChanged
            );
            return;
        }
        applyBackend(context, BackendType.fromTopLevelAndSub(topLevel, TunnelMode.WIREGUARD), onBackendChanged);
    }

    private static void onSubBackendChosen(Fragment fragment, String subValue, OnBackendChanged onBackendChanged) {
        Context context = fragment.requireContext();
        TunnelMode mode = TunnelMode.fromPrefValue(subValue);
        AppPrefs.setVkTurnTunnelMode(context, mode);
        applyBackend(context, BackendType.fromTopLevelAndSub("vk_turn", mode), onBackendChanged);
    }

    private static void applyBackend(Context context, BackendType nextBackend, OnBackendChanged onBackendChanged) {
        ExternalActions.setBackend(context, nextBackend, true, false);
        onBackendChanged.onChanged();
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) {
            if (TextUtils.equals(values[index], target)) {
                return index;
            }
        }
        return -1;
    }

    private static String topLevelTitle(Context context, BackendType backendType) {
        switch (backendType.topLevelGroup()) {
            case "xray":
                return context.getString(R.string.backend_xray_title);
            case "vk_turn":
                return context.getString(R.string.backend_top_vk_turn_title);
            case "wireguard":
                return context.getString(R.string.backend_top_wireguard_title);
            case "amneziawg":
                return context.getString(R.string.backend_top_amneziawg_title);
            default:
                return context.getString(R.string.backend_profiles_selector_summary);
        }
    }

    private static String subBackendTitle(Context context, BackendType backendType) {
        return backendType == BackendType.AMNEZIAWG
            ? context.getString(R.string.backend_profiles_vk_turn_transport_awg)
            : context.getString(R.string.backend_profiles_vk_turn_transport_wg);
    }
}
