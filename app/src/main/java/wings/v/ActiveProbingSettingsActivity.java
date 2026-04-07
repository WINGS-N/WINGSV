package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import wings.v.databinding.ActivityActiveProbingSettingsBinding;
import wings.v.ui.ActiveProbingSettingsFragment;

public class ActiveProbingSettingsActivity extends AppCompatActivity {
    public static Intent createIntent(Context context) {
        return new Intent(context, ActiveProbingSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityActiveProbingSettingsBinding binding =
                ActivityActiveProbingSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.active_probing_settings_container, new ActiveProbingSettingsFragment())
                    .commit();
        }
    }
}
