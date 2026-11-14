package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityActiveProbingSettingsBinding;
import wings.v.ui.ActiveProbingSettingsFragment;

/** Hosts the active probing settings screen */
public class ActiveProbingSettingsActivity extends AppCompatActivity {

    public ActiveProbingSettingsActivity() {
        super();
    }

    public static Intent createIntent(final Context context) {
        return new Intent(context, ActiveProbingSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityActiveProbingSettingsBinding binding = ActivityActiveProbingSettingsBinding.inflate(
            getLayoutInflater()
        );
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.active_probing_settings_container, new ActiveProbingSettingsFragment())
                .commit();
        }
    }
}
