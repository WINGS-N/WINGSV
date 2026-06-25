package wings.v.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import wings.v.ExternalActions;
import wings.v.R;
import wings.v.core.AmneziaProfile;
import wings.v.core.AmneziaProfileStore;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.TunnelMode;
import wings.v.core.UiFormatter;
import wings.v.core.VkTurnProfile;
import wings.v.core.VkTurnProfileStore;
import wings.v.core.WingsImportParser;
import wings.v.core.WireGuardProfile;
import wings.v.core.WireGuardProfileStore;
import wings.v.core.XrayStore;
import wings.v.databinding.FragmentBackendProfilesBinding;
import wings.v.databinding.ItemBackendProfileEntryBinding;

/**
 * Backend-aware profiles screen. The first element is a backend-selector dropdown
 * (same top-level + sub-backend values as the settings dropdown). Selecting a
 * backend switches the active backend through ExternalActions.setBackend (the
 * same persistence the settings dropdown uses) and swaps the shown profile list.
 *
 * For Xray the existing ProfilesFragment is embedded unchanged. For WireGuard,
 * AmneziaWG and VK TURN a simple list backed by the matching per-profile store is
 * rendered with select/favorite/stats/delete-with-cascade-modal and an import
 * entry. Sharing and editing are stubbed pending dedicated follow-up components.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.ExcessiveImports",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.CommentDefaultAccessModifier",
        "PMD.AtLeastOneConstructor",
        "PMD.CouplingBetweenObjects",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.AvoidDuplicateLiterals",
        "PMD.ConfusingTernary",
        // shareProfileStub / editProfileStub keep their params for the dedicated
        // share + editor follow-up components that will consume them.
        "PMD.UnusedFormalParameter",
    }
)
public class BackendProfilesFragment extends Fragment {

    private static final String XRAY_CHILD_TAG = "embedded_xray_profiles";

    private FragmentBackendProfilesBinding binding;

    // Set when we open the per-backend settings screen as a UI editor for a
    // specific profile; on return we fold the flat-key edits back into that
    // active profile so the profile stays in sync with what the user changed.
    @Nullable
    private BackendType pendingUiEditBackend;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentBackendProfilesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.rowBackendSelector.setTitle(getString(R.string.backend_profiles_selector_title));
        binding.rowBackendSelector.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showBackendSelectorMenu(v);
        });
        binding.rowBackendAddProfile.setTitle(getString(R.string.backend_profiles_add_title));
        binding.rowBackendAddProfile.setSummary(getString(R.string.backend_profiles_add_summary));
        binding.rowBackendAddProfile.setOnClickListener(v -> {
            Haptics.softSelection(v);
            importFromClipboard();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingUiEditBackend != null && isAdded()) {
            updateActiveFromFlatPrefs(requireContext(), pendingUiEditBackend);
            pendingUiEditBackend = null;
        }
        refreshUi();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void refreshUi() {
        if (binding == null || !isAdded()) {
            return;
        }
        BackendType backendType = XrayStore.getBackendType(requireContext());
        binding.rowBackendSelector.setSummary(backendSelectorSummary(backendType));
        if (backendType.usesXrayCore()) {
            showXrayList();
        } else {
            showSimpleList(backendType);
        }
    }

    // Backend selector dropdown (top-level + sub-backend), mirroring settings.

    private void showBackendSelectorMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_xray_title);
        menu.getMenu().add(0, 1, 1, R.string.backend_top_vk_turn_title);
        menu.getMenu().add(0, 2, 2, R.string.backend_top_wireguard_title);
        menu.getMenu().add(0, 3, 3, R.string.backend_top_amneziawg_title);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0:
                    selectTopLevel("xray");
                    return true;
                case 1:
                    showVkTurnSubBackendMenu(anchor);
                    return true;
                case 2:
                    selectTopLevel("wireguard");
                    return true;
                case 3:
                    selectTopLevel("amneziawg");
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void showVkTurnSubBackendMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_profiles_sub_backend_wireguard);
        menu.getMenu().add(0, 1, 1, R.string.backend_profiles_sub_backend_amneziawg);
        menu.setOnMenuItemClickListener(item -> {
            TunnelMode mode = item.getItemId() == 1 ? TunnelMode.AMNEZIAWG : TunnelMode.WIREGUARD;
            AppPrefs.setVkTurnTunnelMode(requireContext(), mode);
            applyBackend(BackendType.fromTopLevelAndSub("vk_turn", mode));
            return true;
        });
        menu.show();
    }

    private void selectTopLevel(String topLevel) {
        TunnelMode subMode = TunnelMode.WIREGUARD;
        if ("vk_turn".equals(topLevel)) {
            subMode = AppPrefs.getVkTurnTunnelMode(requireContext());
        }
        applyBackend(BackendType.fromTopLevelAndSub(topLevel, subMode));
    }

    private void applyBackend(BackendType nextBackend) {
        ExternalActions.setBackend(requireContext(), nextBackend, true, false);
        refreshUi();
    }

    private String backendSelectorSummary(BackendType backendType) {
        switch (backendType.topLevelGroup()) {
            case "xray":
                return getString(R.string.backend_xray_title);
            case "vk_turn":
                return (
                    getString(R.string.backend_top_vk_turn_title) +
                    " " +
                    (backendType == BackendType.AMNEZIAWG
                        ? getString(R.string.backend_profiles_vk_turn_transport_awg)
                        : getString(R.string.backend_profiles_vk_turn_transport_wg))
                );
            case "wireguard":
                return getString(R.string.backend_top_wireguard_title);
            case "amneziawg":
                return getString(R.string.backend_top_amneziawg_title);
            default:
                return getString(R.string.backend_profiles_selector_summary);
        }
    }

    // Xray: embed the existing ProfilesFragment unchanged.

    private void showXrayList() {
        binding.scrollSimpleProfiles.setVisibility(View.GONE);
        binding.containerXrayProfiles.setVisibility(View.VISIBLE);
        if (getChildFragmentManager().findFragmentByTag(XRAY_CHILD_TAG) == null) {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.container_xray_profiles, new ProfilesFragment(), XRAY_CHILD_TAG)
                .commit();
        }
    }

    private void removeXrayChildIfPresent() {
        Fragment child = getChildFragmentManager().findFragmentByTag(XRAY_CHILD_TAG);
        if (child != null) {
            getChildFragmentManager().beginTransaction().remove(child).commitAllowingStateLoss();
        }
    }

    // Simple list for WireGuard / AmneziaWG / VK TURN.

    private void showSimpleList(BackendType backendType) {
        removeXrayChildIfPresent();
        binding.containerXrayProfiles.setVisibility(View.GONE);
        binding.scrollSimpleProfiles.setVisibility(View.VISIBLE);

        Context context = requireContext();
        List<SimpleProfile> profiles = loadProfiles(context, backendType);
        String activeId = activeProfileId(context, backendType);
        Map<String, XrayStore.ProfileTrafficStats> traffic = trafficStatsMap(context, backendType);

        binding.containerBackendProfiles.removeAllViews();
        binding.textBackendProfilesEmpty.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);
        binding.containerBackendProfiles.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(context);
        for (int index = 0; index < profiles.size(); index++) {
            SimpleProfile profile = profiles.get(index);
            ItemBackendProfileEntryBinding rowBinding = ItemBackendProfileEntryBinding.inflate(
                inflater,
                binding.containerBackendProfiles,
                false
            );
            bindRow(rowBinding, backendType, profile, activeId, traffic, index < profiles.size() - 1);
            binding.containerBackendProfiles.addView(rowBinding.getRoot());
        }
    }

    private void bindRow(
        ItemBackendProfileEntryBinding rowBinding,
        BackendType backendType,
        SimpleProfile profile,
        String activeId,
        Map<String, XrayStore.ProfileTrafficStats> traffic,
        boolean showDivider
    ) {
        Context context = requireContext();
        rowBinding.textBackendProfileTitle.setText(profile.displayTitle(context));
        rowBinding.textBackendProfileSummary.setText(profile.displaySummary(context));
        rowBinding.viewBackendProfileDivider.setVisibility(showDivider ? View.VISIBLE : View.GONE);

        boolean active = TextUtils.equals(activeId, profile.id);
        rowBinding.imageBackendProfileActive.setVisibility(active ? View.VISIBLE : View.INVISIBLE);

        XrayStore.ProfileTrafficStats stats = traffic.get(profile.id);
        if (stats == null) {
            stats = XrayStore.ProfileTrafficStats.ZERO;
        }
        boolean hasTraffic = stats.rxBytes > 0L || stats.txBytes > 0L;
        rowBinding.layoutBackendProfileTraffic.setVisibility(hasTraffic ? View.VISIBLE : View.GONE);
        rowBinding.textBackendProfileRx.setText(UiFormatter.formatBytes(context, stats.rxBytes));
        rowBinding.textBackendProfileTx.setText(UiFormatter.formatBytes(context, stats.txBytes));

        applyFavoriteState(rowBinding, backendType, profile.id);

        rowBinding.rowBackendProfileEntry.setOnClickListener(v -> onProfileSelected(backendType, profile.id));
        rowBinding.buttonBackendProfileFavorite.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleFavorite(backendType, profile.id);
            refreshUi();
        });
        rowBinding.buttonBackendProfileOverflow.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showProfileMenu(v, backendType, profile);
        });
    }

    private void applyFavoriteState(ItemBackendProfileEntryBinding rowBinding, BackendType backendType, String id) {
        boolean favorite = isFavorite(requireContext(), backendType, id);
        rowBinding.buttonBackendProfileFavorite.setImageResource(
            favorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline
        );
        ImageViewCompat.setImageTintList(
            rowBinding.buttonBackendProfileFavorite,
            ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), favorite ? R.color.wingsv_accent : android.R.color.darker_gray)
            )
        );
        rowBinding.buttonBackendProfileFavorite.setContentDescription(
            getString(
                favorite
                    ? R.string.backend_profiles_action_favorite_remove
                    : R.string.backend_profiles_action_favorite_add
            )
        );
    }

    private void onProfileSelected(BackendType backendType, String profileId) {
        Context context = requireContext();
        if (TextUtils.equals(activeProfileId(context, backendType), profileId)) {
            refreshUi();
            return;
        }
        setActiveProfileId(context, backendType, profileId);
        applyActiveToPrefs(context, backendType);
        wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile changed");
        Haptics.softSelection(binding.getRoot());
        refreshUi();
        Toast.makeText(context, R.string.backend_profiles_selected, Toast.LENGTH_SHORT).show();
    }

    private void showProfileMenu(View anchor, BackendType backendType, SimpleProfile profile) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_profiles_action_select);
        menu.getMenu().add(0, 1, 1, R.string.backend_profiles_action_rename);
        menu.getMenu().add(0, 2, 2, R.string.backend_profiles_action_reset_stats);
        menu.getMenu().add(0, 3, 3, R.string.backend_profiles_action_share);
        menu.getMenu().add(0, 4, 4, R.string.backend_profiles_action_edit);
        menu.getMenu().add(0, 5, 5, R.string.backend_profiles_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0:
                    onProfileSelected(backendType, profile.id);
                    return true;
                case 1:
                    showRenameDialog(backendType, profile);
                    return true;
                case 2:
                    resetStats(backendType, profile.id);
                    return true;
                case 3:
                    shareProfileStub(backendType, profile);
                    return true;
                case 4:
                    editProfile(backendType, profile);
                    return true;
                case 5:
                    confirmDelete(backendType, profile);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void resetStats(BackendType backendType, String profileId) {
        List<String> ids = new ArrayList<>();
        ids.add(profileId);
        resetProfileTrafficStats(requireContext(), backendType, ids);
        refreshUi();
        Toast.makeText(requireContext(), R.string.backend_profiles_reset_stats_done, Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(profile.rawTitle);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_rename_title)
            .setView(input)
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_dialog_ok, (dialog, which) -> {
                String newTitle = input.getText() == null ? "" : input.getText().toString().trim();
                renameProfile(backendType, profile, newTitle);
                refreshUi();
                Toast.makeText(context, R.string.backend_profiles_rename_done, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void confirmDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        if (backendType == BackendType.WIREGUARD || backendType == BackendType.AMNEZIAWG_PLAIN) {
            String transportKind =
                backendType == BackendType.AMNEZIAWG_PLAIN
                    ? VkTurnProfile.TRANSPORT_KIND_AWG
                    : VkTurnProfile.TRANSPORT_KIND_WG;
            List<VkTurnProfile> dependents = VkTurnProfileStore.findVkTurnProfilesReferencing(
                context,
                transportKind,
                profile.id
            );
            if (!dependents.isEmpty()) {
                showCascadeDeleteDialog(backendType, profile, dependents);
                return;
            }
        }
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_delete_confirm_title)
            .setMessage(getString(R.string.backend_profiles_delete_confirm_message, profile.displayTitle(context)))
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_action_delete, (dialog, which) ->
                performPlainDelete(backendType, profile)
            )
            .show();
    }

    private void showCascadeDeleteDialog(
        BackendType backendType,
        SimpleProfile profile,
        List<VkTurnProfile> dependents
    ) {
        Context context = requireContext();
        StringBuilder names = new StringBuilder();
        for (VkTurnProfile dependent : dependents) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(
                TextUtils.isEmpty(dependent.title) ? getString(R.string.backend_profiles_untitled) : dependent.title
            );
        }
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_delete_cascade_title)
            .setMessage(
                getString(R.string.backend_profiles_delete_cascade_message, dependents.size(), names.toString())
            )
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .setPositiveButton(R.string.backend_profiles_delete_cascade_confirm, (dialog, which) ->
                performCascadeDelete(backendType, profile)
            )
            .show();
    }

    private void performPlainDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        boolean removed = deleteProfile(context, backendType, profile.id);
        afterDelete(context, backendType, removed);
    }

    private void performCascadeDelete(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        boolean removed;
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            removed = AmneziaProfileStore.deleteProfileCascade(context, profile.id);
        } else {
            removed = WireGuardProfileStore.deleteProfileCascade(context, profile.id);
        }
        afterDelete(context, backendType, removed);
    }

    private void afterDelete(Context context, BackendType backendType, boolean removed) {
        if (removed) {
            applyActiveToPrefs(context, backendType);
            wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile deleted");
        }
        refreshUi();
        Toast.makeText(
            context,
            removed ? R.string.backend_profiles_delete_done : R.string.backend_profiles_delete_failed,
            Toast.LENGTH_SHORT
        ).show();
    }

    // Sharing and editing are separate follow-up components. Buttons are present
    // and wired to TODO stubs here so the list mirrors the Xray actions.

    private void shareProfileStub(BackendType backendType, SimpleProfile profile) {
        // TODO: wire the per-backend share-link builder once that component lands.
        Toast.makeText(requireContext(), R.string.backend_profiles_share_stub, Toast.LENGTH_SHORT).show();
    }

    // Opens the per-backend editor for this profile. VK TURN goes straight to its
    // form editor (which offers the endpoint + transport reference and an "open UI
    // editor" button). WireGuard / AmneziaWG offer a small chooser between the
    // wg-quick / awg-quick TEXT editor and the structured UI settings editor.
    private void editProfile(BackendType backendType, SimpleProfile profile) {
        // The list dispatch: usesAmneziaSettings() and WIREGUARD map to a WG/AWG
        // transport profile row (text + UI chooser); everything else here is a
        // VkTurnProfile row (the VK TURN form editor).
        if (backendType.usesAmneziaSettings() || backendType == BackendType.WIREGUARD) {
            showBackendEditChooser(backendType, profile);
            return;
        }
        startActivity(wings.v.VkTurnProfileEditorActivity.createIntent(requireContext(), profile.id));
    }

    private void showBackendEditChooser(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        CharSequence[] items = {
            getString(R.string.backend_profile_edit_choice_text),
            getString(R.string.backend_profile_edit_choice_ui),
        };
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_action_edit)
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    startActivity(wings.v.BackendProfileEditorActivity.createIntent(context, backendType, profile.id));
                } else {
                    openUiSettingsForProfile(backendType, profile);
                }
            })
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .show();
    }

    // UI editor entry point for WG / AWG: make the profile active, project it onto
    // the flat keys and open the per-backend settings screen. Folding the flat
    // edits back into the active profile happens in the settings screen path on
    // its next select/apply; here we only ensure the settings open scoped to this
    // profile's values.
    private void openUiSettingsForProfile(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        setActiveProfileId(context, backendType, profile.id);
        applyActiveToPrefs(context, backendType);
        pendingUiEditBackend = backendType;
        startActivity(wings.v.VkTurnSettingsActivity.createIntent(context));
    }

    private void updateActiveFromFlatPrefs(Context context, BackendType backendType) {
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfileStore.updateActiveFromFlatPrefs(context);
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfileStore.updateActiveFromFlatPrefs(context);
        } else {
            VkTurnProfileStore.updateActiveFromFlatPrefs(context);
        }
    }

    // Import path: route a clipboard config through the existing importer which
    // adds it as a new profile to the matching backend (AppPrefs.applyImportedConfig).

    private void importFromClipboard() {
        Context context = requireContext();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            Toast.makeText(context, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clipData.getItemAt(0).coerceToText(context);
        String rawText = text == null ? null : text.toString();
        try {
            WingsImportParser.ImportedConfig importedConfig = WingsImportParser.parseFromText(rawText);
            AppPrefs.applyImportedConfig(context, importedConfig);
            wings.v.service.ProxyTunnelService.requestReconnect(context, "Backend profile imported");
            refreshUi();
            Toast.makeText(context, R.string.backend_profiles_import_success, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_import_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    // Store dispatch helpers. Each maps a non-Xray BackendType to its store.

    private List<SimpleProfile> loadProfiles(Context context, BackendType backendType) {
        ArrayList<SimpleProfile> result = new ArrayList<>();
        if (backendType.usesAmneziaSettings()) {
            for (AmneziaProfile profile : AmneziaProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromAmnezia(profile));
            }
        } else if (backendType == BackendType.WIREGUARD) {
            for (WireGuardProfile profile : WireGuardProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromWireGuard(profile));
            }
        } else {
            for (VkTurnProfile profile : VkTurnProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromVkTurn(profile));
            }
        }
        return result;
    }

    private String activeProfileId(Context context, BackendType backendType) {
        if (backendType.usesAmneziaSettings()) {
            return AmneziaProfileStore.getActiveProfileId(context);
        }
        if (backendType == BackendType.WIREGUARD) {
            return WireGuardProfileStore.getActiveProfileId(context);
        }
        return VkTurnProfileStore.getActiveProfileId(context);
    }

    private void setActiveProfileId(Context context, BackendType backendType, String profileId) {
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfileStore.setActiveProfileId(context, profileId);
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfileStore.setActiveProfileId(context, profileId);
        } else {
            VkTurnProfileStore.setActiveProfileId(context, profileId);
        }
    }

    private void applyActiveToPrefs(Context context, BackendType backendType) {
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfileStore.applyActiveToPrefs(context);
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfileStore.applyActiveToPrefs(context);
        } else {
            VkTurnProfileStore.applyActiveToPrefs(context);
        }
    }

    private Map<String, XrayStore.ProfileTrafficStats> trafficStatsMap(Context context, BackendType backendType) {
        if (backendType.usesAmneziaSettings()) {
            return AmneziaProfileStore.getProfileTrafficStatsMap(context);
        }
        if (backendType == BackendType.WIREGUARD) {
            return WireGuardProfileStore.getProfileTrafficStatsMap(context);
        }
        return VkTurnProfileStore.getProfileTrafficStatsMap(context);
    }

    private void resetProfileTrafficStats(Context context, BackendType backendType, List<String> ids) {
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfileStore.resetProfileTrafficStats(context, ids);
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfileStore.resetProfileTrafficStats(context, ids);
        } else {
            VkTurnProfileStore.resetProfileTrafficStats(context, ids);
        }
    }

    private boolean isFavorite(Context context, BackendType backendType, String id) {
        if (backendType.usesAmneziaSettings()) {
            return AmneziaProfileStore.isFavorite(context, id);
        }
        if (backendType == BackendType.WIREGUARD) {
            return WireGuardProfileStore.isFavorite(context, id);
        }
        return VkTurnProfileStore.isFavorite(context, id);
    }

    private void toggleFavorite(BackendType backendType, String id) {
        Context context = requireContext();
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfileStore.toggleFavorite(context, id);
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfileStore.toggleFavorite(context, id);
        } else {
            VkTurnProfileStore.toggleFavorite(context, id);
        }
    }

    private boolean deleteProfile(Context context, BackendType backendType, String id) {
        if (backendType.usesAmneziaSettings()) {
            return AmneziaProfileStore.deleteProfile(context, id);
        }
        if (backendType == BackendType.WIREGUARD) {
            return WireGuardProfileStore.deleteProfile(context, id);
        }
        return VkTurnProfileStore.deleteProfile(context, id);
    }

    private void renameProfile(BackendType backendType, SimpleProfile profile, String newTitle) {
        Context context = requireContext();
        if (backendType.usesAmneziaSettings()) {
            AmneziaProfile current = AmneziaProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                AmneziaProfileStore.replaceProfile(
                    context,
                    new AmneziaProfile(current.id, newTitle, current.quickConfig)
                );
            }
        } else if (backendType == BackendType.WIREGUARD) {
            WireGuardProfile current = WireGuardProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                WireGuardProfileStore.replaceProfile(
                    context,
                    new WireGuardProfile(
                        current.id,
                        newTitle,
                        current.privateKey,
                        current.addresses,
                        current.dns,
                        current.mtu,
                        current.publicKey,
                        current.presharedKey,
                        current.allowedIps,
                        current.endpoint
                    )
                );
            }
        } else {
            VkTurnProfile current = VkTurnProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                VkTurnProfileStore.replaceProfile(context, renamedVkTurn(current, newTitle));
            }
        }
    }

    private VkTurnProfile renamedVkTurn(VkTurnProfile current, String newTitle) {
        return new VkTurnProfile(
            current.id,
            newTitle,
            current.transportKind,
            current.transportProfileId,
            current.vkTurnEndpoint,
            current.threads,
            current.credsGroupSize,
            current.useUdp,
            current.noObfuscation,
            current.manualCaptcha,
            current.captchaAutoSolver,
            current.vkAuthMode,
            current.turnSessionMode,
            current.dnsMode,
            current.userDns,
            current.runtimeMode,
            current.restartOnNetworkChange,
            current.wrapMode,
            current.wrapCipher,
            current.wrapKeyHex,
            current.wrapSendKey,
            current.localEndpoint,
            current.turnHost,
            current.turnPort
        );
    }

    // Lightweight view model that flattens the three profile types for the row.
    private static final class SimpleProfile {

        final String id;
        final String rawTitle;
        final String summary;

        private SimpleProfile(String id, String rawTitle, String summary) {
            this.id = id == null ? "" : id;
            this.rawTitle = rawTitle == null ? "" : rawTitle;
            this.summary = summary == null ? "" : summary;
        }

        static SimpleProfile fromWireGuard(WireGuardProfile profile) {
            return new SimpleProfile(profile.id, profile.title, profile.endpoint);
        }

        static SimpleProfile fromAmnezia(AmneziaProfile profile) {
            return new SimpleProfile(profile.id, profile.title, "");
        }

        static SimpleProfile fromVkTurn(VkTurnProfile profile) {
            String transport = VkTurnProfile.TRANSPORT_KIND_AWG.equals(profile.transportKind) ? "AWG" : "WG";
            String summary = profile.vkTurnEndpoint;
            if (!TextUtils.isEmpty(summary)) {
                summary = summary + " (" + transport + ")";
            } else {
                summary = transport;
            }
            return new SimpleProfile(profile.id, profile.title, summary);
        }

        String displayTitle(Context context) {
            if (!TextUtils.isEmpty(rawTitle)) {
                return rawTitle;
            }
            if (!TextUtils.isEmpty(summary)) {
                return summary;
            }
            return context.getString(R.string.backend_profiles_untitled);
        }

        String displaySummary(Context context) {
            if (!TextUtils.isEmpty(summary) && !summary.equals(rawTitle)) {
                return summary;
            }
            return shortId();
        }

        private String shortId() {
            if (id.length() <= 8) {
                return id;
            }
            return id.substring(0, 8).toLowerCase(Locale.ROOT);
        }
    }
}
