package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.databinding.ActivityXraySettingsBinding;
import wings.v.ui.XraySettingsFragment;

public class XraySettingsActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, XraySettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityXraySettingsBinding binding = ActivityXraySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.xray_settings_container, new XraySettingsFragment())
                .commit();
        }
    }
}
