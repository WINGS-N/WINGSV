package wings.v.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import wings.v.core.WireGuardProfileEditorCodec;
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
 * rendered with select/favorite/stats/delete-with-cascade-modal, sharing
 * (wingsv:// link, plus raw wg-quick / awg-quick text for WG / AWG), editing and
 * an import entry.
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
        // Some per-backend helpers keep a backendType / profile param for symmetry
        // across the WG / AWG / VK TURN dispatch even when one branch ignores it.
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
            showBackendSelectorMenu();
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

    // Backend selector, mirroring the settings screen's backend dropdown. The
    // settings screen uses an androidx ListPreference (pref_backend_top) backed by
    // the backend_top_entries / backend_top_values arrays, which renders a
    // single-choice dialog with a CHECKMARK on the active entry. This fragment is a
    // plain Fragment (not a PreferenceFragmentCompat), so we reproduce the same UX
    // with an AlertDialog.setSingleChoiceItems (identical single-choice list with a
    // radio mark on the active top-level), driven by the SAME arrays and values, and
    // the SAME wb_stream "unavailable" behavior. vk_turn and wb_stream additionally
    // offer the sub-backend single-choice dialog (tunnel_mode_entries / values).

    private void showBackendSelectorMenu() {
        Context context = requireContext();
        String[] entries = getResources().getStringArray(R.array.backend_top_entries);
        String[] values = getResources().getStringArray(R.array.backend_top_values);
        String activeTop = XrayStore.getBackendType(context).topLevelGroup();
        int checkedIndex = indexOf(values, activeTop);
        new AlertDialog.Builder(context)
            .setTitle(R.string.backend_profiles_selector_title)
            .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                dialog.dismiss();
                onTopLevelChosen(values[which]);
            })
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .show();
    }

    private void onTopLevelChosen(String topLevel) {
        Context context = requireContext();
        if ("wb_stream".equals(topLevel)) {
            // Match the settings dropdown: WB Stream is listed but unavailable.
            Toast.makeText(context, R.string.backend_top_wb_stream_unavailable_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        if ("vk_turn".equals(topLevel)) {
            showSubBackendMenu("vk_turn");
            return;
        }
        applyBackend(BackendType.fromTopLevelAndSub(topLevel, TunnelMode.WIREGUARD));
    }

    private void showSubBackendMenu(String topLevel) {
        Context context = requireContext();
        String[] entries = getResources().getStringArray(R.array.tunnel_mode_entries);
        String[] values = getResources().getStringArray(R.array.tunnel_mode_values);
        TunnelMode current = "wb_stream".equals(topLevel)
            ? AppPrefs.getWbStreamTunnelMode(context)
            : AppPrefs.getVkTurnTunnelMode(context);
        int checkedIndex = indexOf(values, current.prefValue);
        new AlertDialog.Builder(context)
            .setTitle(R.string.sub_backend_title)
            .setSingleChoiceItems(entries, checkedIndex, (dialog, which) -> {
                dialog.dismiss();
                TunnelMode mode = TunnelMode.fromPrefValue(values[which]);
                if ("wb_stream".equals(topLevel)) {
                    AppPrefs.setWbStreamTunnelMode(context, mode);
                } else {
                    AppPrefs.setVkTurnTunnelMode(context, mode);
                }
                applyBackend(BackendType.fromTopLevelAndSub(topLevel, mode));
            })
            .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
            .show();
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) {
            if (TextUtils.equals(values[index], target)) {
                return index;
            }
        }
        return -1;
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
        // Xray manages its own import/add affordance and its own scrolling profile
        // list inside the embedded fragment. Here the shared Actions section keeps
        // only the backend selector (add-profile and the Profiles list section are
        // hidden), and the scroll wraps its content so the embedded Xray fragment
        // takes the remaining space below it.
        binding.rowBackendAddProfile.setVisibility(View.GONE);
        binding.sectionBackendProfilesList.setVisibility(View.GONE);
        setSimpleScrollFills(false);
        binding.scrollSimpleProfiles.setVisibility(View.VISIBLE);
        binding.containerXrayProfiles.setVisibility(View.VISIBLE);
        if (getChildFragmentManager().findFragmentByTag(XRAY_CHILD_TAG) == null) {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.container_xray_profiles, new ProfilesFragment(), XRAY_CHILD_TAG)
                .commit();
        }
    }

    // Toggles whether the simple-profiles scroll fills the remaining space (the
    // WireGuard / AmneziaWG / VK TURN list case) or wraps its content so the
    // embedded Xray fragment below can take the rest (the Xray case). The bottom
    // padding clears the bottom navigation only when the list scrolls here.
    private void setSimpleScrollFills(boolean fills) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) binding.scrollSimpleProfiles.getLayoutParams();
        params.height = fills ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        params.weight = fills ? 1f : 0f;
        binding.scrollSimpleProfiles.setLayoutParams(params);
        int bottomPadding = fills ? dpToPx(104) : dpToPx(4);
        binding.scrollSimpleProfiles.setPadding(
            binding.scrollSimpleProfiles.getPaddingLeft(),
            binding.scrollSimpleProfiles.getPaddingTop(),
            binding.scrollSimpleProfiles.getPaddingRight(),
            bottomPadding
        );
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
        binding.rowBackendAddProfile.setVisibility(View.VISIBLE);
        binding.sectionBackendProfilesList.setVisibility(View.VISIBLE);
        binding.containerXrayProfiles.setVisibility(View.GONE);
        setSimpleScrollFills(true);
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
        // Mirror the Xray profile list: tap selects, long-press opens the action
        // menu (PopupMenu anchored on the row). No per-row overflow button.
        rowBinding.rowBackendProfileEntry.setOnLongClickListener(v -> {
            Haptics.softSelection(v);
            showProfileMenu(v, backendType, profile);
            return true;
        });
        rowBinding.buttonBackendProfileFavorite.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleFavorite(backendType, profile.id);
            refreshUi();
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
            // Re-tapping the already-active profile: still re-project it onto the
            // flat keys so KEY_ENDPOINT (and the rest) reflect this profile even
            // when it was made active by migration/startup and never applied. This
            // is idempotent and runs no reconnect / toast.
            applyActiveToPrefs(context, backendType);
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

    // Long-press action menu, mirroring the Xray profile list's long-press
    // PopupMenu. Order: make active, rename, edit, share, reset traffic stats,
    // delete (the WG/AWG transport rows fold a cascade-delete confirmation into
    // the delete action).
    private void showProfileMenu(View anchor, BackendType backendType, SimpleProfile profile) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_profiles_action_select);
        menu.getMenu().add(0, 1, 1, R.string.backend_profiles_action_rename);
        menu.getMenu().add(0, 2, 2, R.string.backend_profiles_action_edit);
        menu.getMenu().add(0, 3, 3, R.string.backend_profiles_action_share);
        menu.getMenu().add(0, 4, 4, R.string.backend_profiles_action_reset_stats);
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
                    editProfile(backendType, profile);
                    return true;
                case 3:
                    shareProfile(backendType, profile);
                    return true;
                case 4:
                    resetStats(backendType, profile.id);
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

    // Per-backend sharing, mirroring the Xray share flow (proto+deflate+base64
    // wingsv:// link via the ACTION_SEND share sheet). WG / AWG additionally offer
    // sharing the raw wg-quick / awg-quick TEXT, which is portable to other
    // clients. VK TURN offers only the wingsv:// link (the layered endpoint is not
    // meaningful as raw text), and the link embeds the referenced transport so it
    // reconstructs on a device where the transport id does not exist.
    private void shareProfile(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        if (!isVkTurn(backendType)) {
            CharSequence[] items = {
                getString(R.string.backend_profile_share_choice_link),
                getString(R.string.backend_profile_share_choice_text),
            };
            new AlertDialog.Builder(context)
                .setTitle(R.string.backend_profiles_action_share)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        shareTransportLink(backendType, profile);
                    } else {
                        shareTransportText(backendType, profile);
                    }
                })
                .setNegativeButton(R.string.backend_profiles_dialog_cancel, null)
                .show();
            return;
        }
        // VK TURN: link only.
        VkTurnProfile vkTurn = VkTurnProfileStore.getProfileById(context, profile.id);
        if (vkTurn == null) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sendShareText(WingsImportParser.buildTurnProfileLink(context, vkTurn));
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTransportLink(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        try {
            String link;
            if (backendType == BackendType.AMNEZIAWG_PLAIN) {
                AmneziaProfile amnezia = AmneziaProfileStore.getProfileById(context, profile.id);
                if (amnezia == null) {
                    throw new IllegalArgumentException("AmneziaWG profile not found");
                }
                link = WingsImportParser.buildAmneziaProfileLink(amnezia);
            } else {
                WireGuardProfile wireGuard = WireGuardProfileStore.getProfileById(context, profile.id);
                if (wireGuard == null) {
                    throw new IllegalArgumentException("WireGuard profile not found");
                }
                link = WingsImportParser.buildWireGuardProfileLink(wireGuard);
            }
            sendShareText(link);
        } catch (Exception ignored) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTransportText(BackendType backendType, SimpleProfile profile) {
        Context context = requireContext();
        String text;
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfile amnezia = AmneziaProfileStore.getProfileById(context, profile.id);
            text = amnezia == null ? "" : amnezia.quickConfig;
        } else {
            WireGuardProfile wireGuard = WireGuardProfileStore.getProfileById(context, profile.id);
            text = wireGuard == null ? "" : WireGuardProfileEditorCodec.toEditableQuickConfig(wireGuard);
        }
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        sendShareText(text);
    }

    private void sendShareText(String sharedText) {
        Context context = requireContext();
        Intent sendIntent = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, sharedText);
        Intent chooserIntent = Intent.createChooser(sendIntent, getString(R.string.xray_profiles_share_chooser));
        if (chooserIntent.resolveActivity(context.getPackageManager()) == null) {
            Toast.makeText(context, R.string.backend_profiles_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(chooserIntent);
    }

    // Opens the per-backend editor for this profile. VK TURN goes straight to its
    // form editor (which offers the endpoint + transport reference and an "open UI
    // editor" button). WireGuard / AmneziaWG offer a small chooser between the
    // wg-quick / awg-quick TEXT editor and the structured UI settings editor.
    private void editProfile(BackendType backendType, SimpleProfile profile) {
        // The list dispatch: the two plain backends (WIREGUARD, AMNEZIAWG_PLAIN)
        // map to a WG/AWG transport profile row (text + UI chooser); the two VK
        // TURN variants map to a VkTurnProfile row (the VK TURN form editor).
        if (!isVkTurn(backendType)) {
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
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.updateActiveFromFlatPrefs(context);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.updateActiveFromFlatPrefs(context);
        } else {
            WireGuardProfileStore.updateActiveFromFlatPrefs(context);
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
    //
    // The simple list serves four BackendType values: plain WireGuard
    // (WIREGUARD), plain AmneziaWG (AMNEZIAWG_PLAIN), and the two VK TURN
    // variants VK_TURN_WIREGUARD (WG transport) and AMNEZIAWG (AWG transport).
    // Both VK TURN variants share topLevelGroup() == "vk_turn" and must route to
    // VkTurnProfileStore; only the two plain backends route to the transport
    // stores. Keying on usesAmneziaSettings() would mis-route AMNEZIAWG (a VK TURN
    // backend) to AmneziaProfileStore, which never projects the VK TURN endpoint
    // onto KEY_ENDPOINT and would show the wrong list.

    private static boolean isVkTurn(BackendType backendType) {
        return "vk_turn".equals(backendType.topLevelGroup());
    }

    private List<SimpleProfile> loadProfiles(Context context, BackendType backendType) {
        ArrayList<SimpleProfile> result = new ArrayList<>();
        if (isVkTurn(backendType)) {
            for (VkTurnProfile profile : VkTurnProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromVkTurn(profile));
            }
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            for (AmneziaProfile profile : AmneziaProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromAmnezia(profile));
            }
        } else {
            for (WireGuardProfile profile : WireGuardProfileStore.getProfiles(context)) {
                result.add(SimpleProfile.fromWireGuard(profile));
            }
        }
        return result;
    }

    private String activeProfileId(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.getActiveProfileId(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.getActiveProfileId(context);
        }
        return WireGuardProfileStore.getActiveProfileId(context);
    }

    private void setActiveProfileId(Context context, BackendType backendType, String profileId) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.setActiveProfileId(context, profileId);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.setActiveProfileId(context, profileId);
        } else {
            WireGuardProfileStore.setActiveProfileId(context, profileId);
        }
    }

    private void applyActiveToPrefs(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.applyActiveToPrefs(context);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.applyActiveToPrefs(context);
        } else {
            WireGuardProfileStore.applyActiveToPrefs(context);
        }
    }

    private Map<String, XrayStore.ProfileTrafficStats> trafficStatsMap(Context context, BackendType backendType) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.getProfileTrafficStatsMap(context);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.getProfileTrafficStatsMap(context);
        }
        return WireGuardProfileStore.getProfileTrafficStatsMap(context);
    }

    private void resetProfileTrafficStats(Context context, BackendType backendType, List<String> ids) {
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.resetProfileTrafficStats(context, ids);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.resetProfileTrafficStats(context, ids);
        } else {
            WireGuardProfileStore.resetProfileTrafficStats(context, ids);
        }
    }

    private boolean isFavorite(Context context, BackendType backendType, String id) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.isFavorite(context, id);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.isFavorite(context, id);
        }
        return WireGuardProfileStore.isFavorite(context, id);
    }

    private void toggleFavorite(BackendType backendType, String id) {
        Context context = requireContext();
        if (isVkTurn(backendType)) {
            VkTurnProfileStore.toggleFavorite(context, id);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfileStore.toggleFavorite(context, id);
        } else {
            WireGuardProfileStore.toggleFavorite(context, id);
        }
    }

    private boolean deleteProfile(Context context, BackendType backendType, String id) {
        if (isVkTurn(backendType)) {
            return VkTurnProfileStore.deleteProfile(context, id);
        }
        if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            return AmneziaProfileStore.deleteProfile(context, id);
        }
        return WireGuardProfileStore.deleteProfile(context, id);
    }

    private void renameProfile(BackendType backendType, SimpleProfile profile, String newTitle) {
        Context context = requireContext();
        if (isVkTurn(backendType)) {
            VkTurnProfile current = VkTurnProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                VkTurnProfileStore.replaceProfile(context, renamedVkTurn(current, newTitle));
            }
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            AmneziaProfile current = AmneziaProfileStore.getProfileById(context, profile.id);
            if (current != null) {
                AmneziaProfileStore.replaceProfile(
                    context,
                    new AmneziaProfile(current.id, newTitle, current.quickConfig)
                );
            }
        } else {
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
