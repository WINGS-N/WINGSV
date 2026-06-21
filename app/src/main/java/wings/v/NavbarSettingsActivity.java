package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wings.v.core.UiPrefs;
import wings.v.databinding.ActivityUiReorderListBinding;
import wings.v.ui.UiReorderListController;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public class NavbarSettingsActivity extends AppCompatActivity {

    private ActivityUiReorderListBinding binding;

    public static Intent createIntent(Context context) {
        return new Intent(context, NavbarSettingsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUiReorderListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setTitle(getString(R.string.ui_settings_navbar_title));
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        binding.textHeaderTitle.setText(R.string.ui_settings_navbar_title);
        binding.textHeaderSummary.setText(R.string.ui_settings_drag_hint);

        Map<String, Integer> labels = navbarLabels();
        List<String> savedOrder = UiPrefs.getNavbarOrder(this);
        List<UiReorderListController.Entry> entries = new ArrayList<>(savedOrder.size());
        for (String key : savedOrder) {
            Integer titleRes = labels.get(key);
            if (titleRes == null) {
                continue;
            }
            entries.add(
                new UiReorderListController.Entry(key, getString(titleRes), UiPrefs.NAVBAR_FORCED_VISIBLE.contains(key))
            );
        }

        UiReorderListController controller = new UiReorderListController(
            this,
            entries,
            UiPrefs.getNavbarHidden(this),
            (order, hidden) -> {
                UiPrefs.setNavbarOrder(this, order);
                UiPrefs.setNavbarHidden(this, hidden);
            }
        );
        controller.attach(binding.recyclerItems);
    }

    private Map<String, Integer> navbarLabels() {
        LinkedHashMap<String, Integer> labels = new LinkedHashMap<>();
        labels.put(UiPrefs.NAVBAR_HOME, R.string.navbar_item_home);
        labels.put(UiPrefs.NAVBAR_PROFILES, R.string.navbar_item_profiles);
        labels.put(UiPrefs.NAVBAR_APPS, R.string.navbar_item_apps);
        labels.put(UiPrefs.NAVBAR_SHARING, R.string.navbar_item_sharing);
        labels.put(UiPrefs.NAVBAR_SETTINGS, R.string.navbar_item_settings);
        return labels;
    }
}
