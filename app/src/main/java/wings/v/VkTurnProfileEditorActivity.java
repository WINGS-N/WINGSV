package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import java.util.ArrayList;
import java.util.List;
import wings.v.core.AmneziaProfile;
import wings.v.core.AmneziaProfileStore;
import wings.v.core.AppPrefs;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.TunnelMode;
import wings.v.core.VkTurnProfile;
import wings.v.core.VkTurnProfileStore;
import wings.v.core.WireGuardProfile;
import wings.v.core.WireGuardProfileStore;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityVkTurnProfileEditorBinding;
import wings.v.service.ProxyTunnelService;

/**
 * Editor for a VK TURN profile. It edits the VK TURN endpoint (layered on top of
 * the transport) and which transport the profile references: the transport kind
 * (WireGuard / AmneziaWG) plus which WG/AWG profile by id. The transport's own
 * config is edited through that WG/AWG profile's editor, so this screen only
 * keeps the reference stable. An "open UI editor" button makes the profile
 * active and opens the VK TURN settings screen scoped to it, folding the flat
 * key edits back into the active profile on return.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.CognitiveComplexity",
        "PMD.AvoidCatchingGenericException",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
    }
)
public class VkTurnProfileEditorActivity extends AppCompatActivity {

    private static final String EXTRA_PROFILE_ID = "profile_id";

    private ActivityVkTurnProfileEditorBinding binding;
    private String profileId = "";
    private String transportKind = VkTurnProfile.TRANSPORT_KIND_WG;
    private String transportProfileId = "";
    private boolean returningFromUiEditor;

    public static Intent createIntent(Context context, String profileId) {
        return new Intent(context, VkTurnProfileEditorActivity.class).putExtra(EXTRA_PROFILE_ID, profileId);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVkTurnProfileEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        profileId = stringExtra(EXTRA_PROFILE_ID);
        VkTurnProfile profile = VkTurnProfileStore.getProfileById(this, profileId);
        if (profile == null) {
            finish();
            return;
        }
        transportKind = profile.transportKind;
        transportProfileId = profile.transportProfileId;
        binding.editVkTurnEndpoint.setText(profile.vkTurnEndpoint);
        binding.buttonTransportKind.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showTransportKindMenu(v);
        });
        binding.buttonTransportProfile.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showTransportProfileMenu(v);
        });
        binding.buttonSaveProfileEditor.setOnClickListener(v -> {
            Haptics.softSelection(v);
            saveProfile();
        });
        binding.buttonOpenUiEditor.setOnClickListener(v -> {
            Haptics.softSelection(v);
            openUiEditor();
        });
        refreshTransportLabels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the UI editor folds flat edits back into the active
        // profile; reflect any changed endpoint / transport here.
        if (binding == null || !returningFromUiEditor) {
            return;
        }
        returningFromUiEditor = false;
        if (TextUtils.equals(VkTurnProfileStore.getActiveProfileId(this), profileId)) {
            VkTurnProfileStore.updateActiveFromFlatPrefs(this);
        }
        VkTurnProfile profile = VkTurnProfileStore.getProfileById(this, profileId);
        if (profile != null) {
            transportKind = profile.transportKind;
            transportProfileId = profile.transportProfileId;
            binding.editVkTurnEndpoint.setText(profile.vkTurnEndpoint);
            refreshTransportLabels();
        }
    }

    private void showTransportKindMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, R.string.backend_profiles_sub_backend_wireguard);
        menu.getMenu().add(0, 1, 1, R.string.backend_profiles_sub_backend_amneziawg);
        menu.setOnMenuItemClickListener(item -> {
            String nextKind =
                item.getItemId() == 1 ? VkTurnProfile.TRANSPORT_KIND_AWG : VkTurnProfile.TRANSPORT_KIND_WG;
            if (!TextUtils.equals(nextKind, transportKind)) {
                transportKind = nextKind;
                // Reset the referenced profile when the kind changes; the next
                // chooser picks a profile of the matching transport store.
                transportProfileId = "";
                refreshTransportLabels();
            }
            return true;
        });
        menu.show();
    }

    private void showTransportProfileMenu(View anchor) {
        List<TransportOption> options = transportOptions();
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.vk_turn_profile_editor_no_transport, Toast.LENGTH_SHORT).show();
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int index = 0; index < options.size(); index++) {
            menu.getMenu().add(0, index, index, options.get(index).label);
        }
        menu.setOnMenuItemClickListener(item -> {
            TransportOption option = options.get(item.getItemId());
            transportProfileId = option.id;
            refreshTransportLabels();
            return true;
        });
        menu.show();
    }

    private List<TransportOption> transportOptions() {
        ArrayList<TransportOption> result = new ArrayList<>();
        if (VkTurnProfile.TRANSPORT_KIND_AWG.equals(transportKind)) {
            for (AmneziaProfile profile : AmneziaProfileStore.getProfiles(this)) {
                result.add(new TransportOption(profile.id, transportTitle(profile.title, profile.id)));
            }
        } else {
            for (WireGuardProfile profile : WireGuardProfileStore.getProfiles(this)) {
                String label = TextUtils.isEmpty(profile.title) ? profile.endpoint : profile.title;
                result.add(new TransportOption(profile.id, transportTitle(label, profile.id)));
            }
        }
        return result;
    }

    private String transportTitle(String label, String id) {
        if (!TextUtils.isEmpty(label)) {
            return label;
        }
        return getString(R.string.backend_profiles_untitled) + " " + shortId(id);
    }

    private void refreshTransportLabels() {
        binding.buttonTransportKind.setText(
            VkTurnProfile.TRANSPORT_KIND_AWG.equals(transportKind)
                ? R.string.backend_profiles_sub_backend_amneziawg
                : R.string.backend_profiles_sub_backend_wireguard
        );
        binding.buttonTransportProfile.setText(resolveTransportProfileLabel());
    }

    private String resolveTransportProfileLabel() {
        if (TextUtils.isEmpty(transportProfileId)) {
            return getString(R.string.vk_turn_profile_editor_transport_not_set);
        }
        if (VkTurnProfile.TRANSPORT_KIND_AWG.equals(transportKind)) {
            AmneziaProfile profile = AmneziaProfileStore.getProfileById(this, transportProfileId);
            return profile == null
                ? getString(R.string.vk_turn_profile_editor_transport_not_set)
                : transportTitle(profile.title, profile.id);
        }
        WireGuardProfile profile = WireGuardProfileStore.getProfileById(this, transportProfileId);
        if (profile == null) {
            return getString(R.string.vk_turn_profile_editor_transport_not_set);
        }
        String label = TextUtils.isEmpty(profile.title) ? profile.endpoint : profile.title;
        return transportTitle(label, profile.id);
    }

    private void saveProfile() {
        if (TextUtils.isEmpty(transportProfileId)) {
            Toast.makeText(this, R.string.vk_turn_profile_editor_no_transport, Toast.LENGTH_SHORT).show();
            return;
        }
        VkTurnProfile current = VkTurnProfileStore.getProfileById(this, profileId);
        if (current == null) {
            Toast.makeText(this, R.string.xray_profile_editor_profile_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String endpoint =
            binding.editVkTurnEndpoint.getText() == null ? "" : binding.editVkTurnEndpoint.getText().toString().trim();
        VkTurnProfile updated = withEdits(current, endpoint, transportKind, transportProfileId);
        if (!VkTurnProfileStore.replaceProfile(this, updated)) {
            Toast.makeText(this, R.string.xray_profile_editor_profile_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isActive = TextUtils.equals(VkTurnProfileStore.getActiveProfileId(this), updated.id);
        if (isActive) {
            VkTurnProfileStore.applyActiveToPrefs(this);
            BackendType backendType = XrayStore.getBackendType(this);
            if (backendType != null && backendType.usesTurnProxy() && ProxyTunnelService.isActive()) {
                ProxyTunnelService.requestReconnect(getApplicationContext(), "Backend profile edited");
            }
        }
        Toast.makeText(this, R.string.xray_profile_editor_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openUiEditor() {
        if (TextUtils.isEmpty(transportProfileId)) {
            Toast.makeText(this, R.string.vk_turn_profile_editor_no_transport, Toast.LENGTH_SHORT).show();
            return;
        }
        VkTurnProfile current = VkTurnProfileStore.getProfileById(this, profileId);
        if (current == null) {
            finish();
            return;
        }
        String endpoint =
            binding.editVkTurnEndpoint.getText() == null ? "" : binding.editVkTurnEndpoint.getText().toString().trim();
        VkTurnProfile updated = withEdits(current, endpoint, transportKind, transportProfileId);
        VkTurnProfileStore.replaceProfile(this, updated);
        VkTurnProfileStore.setActiveProfileId(this, updated.id);
        boolean awg = updated.usesAmneziaTransport();
        AppPrefs.setVkTurnTunnelMode(this, awg ? TunnelMode.AMNEZIAWG : TunnelMode.WIREGUARD);
        XrayStore.setBackendType(
            this,
            BackendType.fromTopLevelAndSub("vk_turn", awg ? TunnelMode.AMNEZIAWG : TunnelMode.WIREGUARD)
        );
        VkTurnProfileStore.applyActiveToPrefs(this);
        returningFromUiEditor = true;
        startActivity(VkTurnSettingsActivity.createIntent(this));
    }

    private VkTurnProfile withEdits(VkTurnProfile base, String endpoint, String kind, String transportId) {
        return new VkTurnProfile(
            base.id,
            base.title,
            kind,
            transportId,
            endpoint,
            base.threads,
            base.credsGroupSize,
            base.useUdp,
            base.noObfuscation,
            base.manualCaptcha,
            base.captchaAutoSolver,
            base.vkAuthMode,
            base.turnSessionMode,
            base.dnsMode,
            base.userDns,
            base.runtimeMode,
            base.restartOnNetworkChange,
            base.wrapMode,
            base.wrapCipher,
            base.wrapKeyHex,
            base.wrapSendKey,
            base.localEndpoint,
            base.turnHost,
            base.turnPort
        );
    }

    private String stringExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static final class TransportOption {

        private final String id;
        private final String label;

        private TransportOption(String id, String label) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
        }
    }
}
