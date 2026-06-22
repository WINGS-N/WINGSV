package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityUiSettingsBinding;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public class UiSettingsActivity extends AppCompatActivity {

    private ActivityUiSettingsBinding binding;

    public static Intent createIntent(Context context) {
        return new Intent(context, UiSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUiSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        binding.rowOpenTheme.setTitle(getString(R.string.settings_theme_title));
        binding.rowOpenTheme.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(ThemeSettingsActivity.createIntent(this));
        });

        binding.rowHideIp.setSummary(getString(R.string.settings_hide_ip_summary));
        binding.rowHideIp.setChecked(AppPrefs.isHideIpEnabled(this));
        binding.rowHideIp.setOnCheckedChangedListener((id, checked) -> {
            AppPrefs.setHideIpEnabled(this, checked);
            return kotlin.Unit.INSTANCE;
        });

        binding.rowOpenNavbar.setTitle(getString(R.string.ui_settings_navbar_title));
        binding.rowOpenNavbar.setSummary(getString(R.string.ui_settings_navbar_summary));
        binding.rowOpenNavbar.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(NavbarSettingsActivity.createIntent(this));
        });

        binding.rowOpenNotification.setTitle(getString(R.string.ui_settings_notification_title));
        binding.rowOpenNotification.setSummary(getString(R.string.ui_settings_notification_summary));
        binding.rowOpenNotification.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(NotificationSettingsActivity.createIntent(this));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null) {
            binding.rowOpenTheme.setSummary(themeModeSummary());
            binding.rowHideIp.setChecked(AppPrefs.isHideIpEnabled(this));
        }
    }

    private String themeModeSummary() {
        String mode = AppPrefs.getThemeMode(this);
        if (AppPrefs.THEME_MODE_DARK.equals(mode)) {
            return getString(R.string.theme_mode_dark);
        }
        if (AppPrefs.THEME_MODE_LIGHT.equals(mode)) {
            return getString(R.string.theme_mode_light);
        }
        return getString(R.string.theme_mode_system);
    }
}
