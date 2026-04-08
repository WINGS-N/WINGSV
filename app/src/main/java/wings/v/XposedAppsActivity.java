package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.databinding.ActivityXposedAppsBinding;
import wings.v.ui.XposedAppsFragment;

public class XposedAppsActivity extends AppCompatActivity {

    private static final String EXTRA_MODE = "mode";

    public static final String MODE_TARGET_APPS = "target_apps";
    public static final String MODE_HIDDEN_VPN_APPS = "hidden_vpn_apps";

    public static Intent createIntent(Context context, String mode) {
        return new Intent(context, XposedAppsActivity.class).putExtra(EXTRA_MODE, mode);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityXposedAppsBinding binding = ActivityXposedAppsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_HIDDEN_VPN_APPS.equals(mode)) {
            binding.toolbarLayout.setTitle(getString(R.string.xposed_apps_hidden_vpn_title));
        } else {
            mode = MODE_TARGET_APPS;
            binding.toolbarLayout.setTitle(getString(R.string.xposed_apps_target_title));
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.xposed_apps_container, XposedAppsFragment.create(mode))
                .commit();
        }
    }
}
