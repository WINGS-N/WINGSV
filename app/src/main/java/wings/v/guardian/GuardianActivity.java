package wings.v.guardian;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.R;

/** Hosts the Guardian (popechitelstvo) settings screen. */
public class GuardianActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, GuardianActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_guardian);
        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);
        if (state == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.guardian_settings_container, new GuardianSettingsFragment())
                .commit();
        }
    }
}
