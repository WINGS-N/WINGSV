package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivityXposedSettingsBinding;
import wings.v.ui.XposedSettingsFragment;

public class XposedSettingsActivity extends AppCompatActivity {

    public XposedSettingsActivity() {
        super();
    }

    public static Intent createIntent(final Context context) {
        return new Intent(context, XposedSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityXposedSettingsBinding binding = ActivityXposedSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.xposed_settings_container, new XposedSettingsFragment())
                .commit();
        }
    }
}
