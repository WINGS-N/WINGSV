package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.core.BackendType;
import wings.v.core.XrayStore;
import wings.v.core.XrayTransportMode;
import wings.v.databinding.ActivityVkTurnSettingsBinding;
import wings.v.ui.VkTurnSettingsFragment;

/** Hosts backend-specific WireGuard and VK TURN settings. */
public class VkTurnSettingsActivity extends AppCompatActivity {

    /** Required empty constructor. */
    public VkTurnSettingsActivity() {
        super();
    }

    /** Creates an intent for the backend-specific settings screen. */
    public static Intent createIntent(final Context context) {
        return new Intent(context, VkTurnSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityVkTurnSettingsBinding binding = ActivityVkTurnSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        toolbarLayout.setTitle(resolveTitle());
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.vk_turn_settings_container, new VkTurnSettingsFragment())
                .commit();
        }
    }

    private String resolveTitle() {
        final BackendType backendType = XrayStore.getBackendType(this);
        final XrayTransportMode xrayTransportMode = XrayStore.getXraySettings(this).transportMode;
        String title = getString(R.string.vk_turn_settings_title);
        if (backendType == BackendType.WIREGUARD) {
            title = getString(R.string.wireguard_settings_title);
        } else if (backendType == BackendType.AMNEZIAWG_PLAIN) {
            title = getString(R.string.amneziawg_settings_title);
        } else if (backendType == BackendType.AMNEZIAWG) {
            title = getString(R.string.vk_turn_awg_settings_title);
        }
        return title;
    }
}
