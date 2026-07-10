package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.databinding.ActivityByeDpiAppsBinding;
import wings.v.ui.ByeDpiAppsFragment;

public class ByeDpiAppsActivity extends AppCompatActivity {

    public static Intent createIntent(Context context) {
        return new Intent(context, ByeDpiAppsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityByeDpiAppsBinding binding = ActivityByeDpiAppsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.toolbarLayout.setTitle(getString(R.string.byedpi_apps_title));

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.byedpi_apps_container, new ByeDpiAppsFragment())
                .commit();
        }
    }
}
