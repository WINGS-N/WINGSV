package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityByeDpiSettingsBinding;
import wings.v.ui.ByeDpiSettingsFragment;

/** Hosts the ByeDPI settings screen */
public class ByeDpiSettingsActivity extends AppCompatActivity {

    public ByeDpiSettingsActivity() {
        super();
    }

    public static Intent createIntent(final Context context) {
        return new Intent(context, ByeDpiSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityByeDpiSettingsBinding binding = ActivityByeDpiSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.bydpi_settings_container, new ByeDpiSettingsFragment())
                .commit();
        }
    }
}
