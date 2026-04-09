package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityRootInterfaceSettingsBinding;
import wings.v.ui.RootInterfaceSettingsFragment;

/** Hosts root interface naming settings. */
public class RootInterfaceSettingsActivity extends AppCompatActivity {

    /** Required empty constructor. */
    public RootInterfaceSettingsActivity() {
        super();
    }

    /** Creates an intent for the root interface settings screen. */
    public static Intent createIntent(final Context context) {
        return new Intent(context, RootInterfaceSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityRootInterfaceSettingsBinding binding = ActivityRootInterfaceSettingsBinding.inflate(
            getLayoutInflater()
        );
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.root_interface_settings_container, new RootInterfaceSettingsFragment())
                .commit();
        }
    }
}
