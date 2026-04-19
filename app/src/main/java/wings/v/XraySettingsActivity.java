package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityXraySettingsBinding;
import wings.v.ui.XraySettingsFragment;

/** Hosts the Xray settings screen. */
public class XraySettingsActivity extends AppCompatActivity {

    /** Required empty constructor. */
    public XraySettingsActivity() {
        super();
    }

    /** Creates an intent for the Xray settings screen. */
    public static Intent createIntent(final Context context) {
        return new Intent(context, XraySettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityXraySettingsBinding binding = ActivityXraySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.xray_settings_container, new XraySettingsFragment())
                .commit();
        }
    }
}
