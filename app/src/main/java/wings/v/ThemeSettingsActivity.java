package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.ThemeModeController;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
public class ThemeSettingsActivity extends AppCompatActivity {

    public static Intent createIntent(final Context context) {
        return new Intent(context, ThemeSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_theme_settings);
        ToolbarLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        toolbarLayout.setShowNavigationButtonAsBack(true);
        bindThemeCard(R.id.card_theme_system, AppPrefs.THEME_MODE_SYSTEM);
        bindThemeCard(R.id.card_theme_dark, AppPrefs.THEME_MODE_DARK);
        bindThemeCard(R.id.card_theme_light, AppPrefs.THEME_MODE_LIGHT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSelectionUi();
    }

    private void bindThemeCard(final int viewId, final String themeMode) {
        final View card = findViewById(viewId);
        if (card == null) {
            return;
        }
        card.setOnClickListener(view -> selectTheme(themeMode, view));
    }

    private void selectTheme(final String themeMode, final View sourceView) {
        final String currentMode = AppPrefs.getThemeMode(this);
        if (TextUtils.equals(currentMode, themeMode)) {
            Haptics.softSelection(sourceView);
            return;
        }
        Haptics.softConfirm(sourceView);
        AppPrefs.setThemeMode(this, themeMode);
        ThemeModeController.applyPreferenceValue(themeMode);
        refreshSelectionUi();
    }

    private void refreshSelectionUi() {
        final String currentMode = AppPrefs.getThemeMode(this);
        updateThemeCard(R.id.card_theme_system, R.id.icon_theme_system_selected, AppPrefs.THEME_MODE_SYSTEM, currentMode);
        updateThemeCard(R.id.card_theme_dark, R.id.icon_theme_dark_selected, AppPrefs.THEME_MODE_DARK, currentMode);
        updateThemeCard(R.id.card_theme_light, R.id.icon_theme_light_selected, AppPrefs.THEME_MODE_LIGHT, currentMode);
    }

    private void updateThemeCard(
        final int viewId,
        final int iconId,
        final String optionMode,
        final String currentMode
    ) {
        final View card = findViewById(viewId);
        final ImageView icon = findViewById(iconId);
        if (card == null || icon == null) {
            return;
        }
        final boolean selected = TextUtils.equals(optionMode, currentMode);
        card.setBackgroundResource(selected ? R.drawable.bg_theme_option_selected : R.drawable.bg_surface_card);
        icon.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }
}
