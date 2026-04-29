package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityWbStreamSettingsBinding;
import wings.v.ui.WbStreamSettingsFragment;

public class WbStreamSettingsActivity extends AppCompatActivity {

    public WbStreamSettingsActivity() {
        super();
    }

    public static Intent createIntent(final Context context) {
        return new Intent(context, WbStreamSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityWbStreamSettingsBinding binding = ActivityWbStreamSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        toolbarLayout.setTitle(getString(R.string.wb_stream_settings_title));
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.wb_stream_settings_container, WbStreamSettingsFragment.newInstance())
                .commit();
        }
    }
}
