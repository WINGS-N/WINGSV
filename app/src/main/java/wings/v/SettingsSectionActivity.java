package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.databinding.ActivitySettingsSectionBinding;
import wings.v.ui.SettingsFragment;

/**
 * Раздел настроек: тот же список, открытый со своей ветки.
 *
 * <p>Экран один на все разделы, потому что и настройки одни: фрагменту хватает
 * ключа ветки, а плодить по активити на каждый заголовок значит копировать один
 * и тот же код шесть раз.
 */
public class SettingsSectionActivity extends AppCompatActivity {

    private static final String EXTRA_TITLE = "wings.v.extra.SECTION_TITLE";

    /** Required empty constructor. */
    public SettingsSectionActivity() {
        super();
    }

    /** Открывает ветку настроек с её заголовком в шапке. */
    public static Intent createIntent(final Context context, final String rootKey, final CharSequence title) {
        final Intent intent = new Intent(context, SettingsSectionActivity.class);
        intent.putExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey);
        intent.putExtra(EXTRA_TITLE, title == null ? "" : title.toString());
        return intent;
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivitySettingsSectionBinding binding = ActivitySettingsSectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        final ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        final String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (!TextUtils.isEmpty(title)) {
            toolbarLayout.setTitle(title);
        }
        if (state == null) {
            final Bundle args = new Bundle();
            args.putString(
                PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,
                getIntent().getStringExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT)
            );
            final SettingsFragment fragment = new SettingsFragment();
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction().replace(R.id.settings_section_container, fragment).commit();
        }
    }
}
