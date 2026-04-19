package wings.v;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import java.util.ArrayList;
import java.util.List;
import wings.v.ui.AppsFragment;
import wings.v.ui.HomeFragment;
import wings.v.ui.ProfilesFragment;
import wings.v.ui.SettingsFragment;
import wings.v.ui.SharingFragment;

public class MainPagerAdapter extends FragmentStateAdapter {

    public static final long ITEM_HOME = 100L;
    public static final long ITEM_PROFILES = 101L;
    public static final long ITEM_APPS = 102L;
    public static final long ITEM_SHARING = 103L;
    public static final long ITEM_SETTINGS = 104L;

    private final List<Long> items = new ArrayList<>();

    public MainPagerAdapter(@NonNull AppCompatActivity activity, boolean hasProfilesTab, boolean hasSharingTab) {
        super(activity);
        items.add(ITEM_HOME);
        if (hasProfilesTab) {
            items.add(ITEM_PROFILES);
        }
        items.add(ITEM_APPS);
        if (hasSharingTab) {
            items.add(ITEM_SHARING);
        }
        items.add(ITEM_SETTINGS);
    }

    public int getPageCount() {
        return items.size();
    }

    public int positionForItem(long itemId) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index) == itemId) {
                return index;
            }
        }
        return 0;
    }

    public long getItemAt(int position) {
        if (position < 0 || position >= items.size()) {
            return ITEM_HOME;
        }
        return items.get(position);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        long itemId = getItemId(position);
        if (itemId == ITEM_PROFILES) {
            return new ProfilesFragment();
        }
        if (itemId == ITEM_APPS) {
            return new AppsFragment();
        }
        if (itemId == ITEM_SHARING) {
            return new SharingFragment();
        }
        if (itemId == ITEM_SETTINGS) {
            return new SettingsFragment();
        }
        return new HomeFragment();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return getItemAt(position);
    }

    @Override
    public boolean containsItem(long itemId) {
        return items.contains(itemId);
    }
}
