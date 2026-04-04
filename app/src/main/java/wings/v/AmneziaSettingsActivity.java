package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import wings.v.databinding.ActivityAmneziaSettingsBinding;
import wings.v.ui.AmneziaSettingsFragment;

public class AmneziaSettingsActivity extends AppCompatActivity {
    public static Intent createIntent(Context context) {
        return new Intent(context, AmneziaSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAmneziaSettingsBinding binding = ActivityAmneziaSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.amnezia_settings_container, new AmneziaSettingsFragment())
                    .commit();
        }
    }
}
