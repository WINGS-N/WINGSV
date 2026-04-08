package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.databinding.ActivityXposedSettingsBinding;
import wings.v.ui.XposedSettingsFragment;

public class XposedSettingsActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, XposedSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityXposedSettingsBinding binding = ActivityXposedSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.xposed_settings_container, new XposedSettingsFragment())
                .commit();
        }
    }
}
