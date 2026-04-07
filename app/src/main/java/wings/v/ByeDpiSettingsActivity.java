package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import wings.v.databinding.ActivityByeDpiSettingsBinding;
import wings.v.ui.ByeDpiSettingsFragment;

public class ByeDpiSettingsActivity extends AppCompatActivity {
    public static Intent createIntent(Context context) {
        return new Intent(context, ByeDpiSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityByeDpiSettingsBinding binding = ActivityByeDpiSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.bydpi_settings_container, new ByeDpiSettingsFragment())
                    .commit();
        }
    }
}
