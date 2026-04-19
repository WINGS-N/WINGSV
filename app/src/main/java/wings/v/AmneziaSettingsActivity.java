package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityAmneziaSettingsBinding;
import wings.v.ui.AmneziaSettingsFragment;

/** Hosts the AmneziaWG settings screen. */
public class AmneziaSettingsActivity extends AppCompatActivity {

    /** Required empty constructor. */
    public AmneziaSettingsActivity() {
        super();
    }

    /** Creates an intent for the AmneziaWG settings screen. */
    public static Intent createIntent(final Context context) {
        return new Intent(context, AmneziaSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityAmneziaSettingsBinding binding = ActivityAmneziaSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.amnezia_settings_container, new AmneziaSettingsFragment())
                .commit();
        }
    }
}
