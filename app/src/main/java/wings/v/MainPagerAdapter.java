package wings.v;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import wings.v.ui.AppsFragment;
import wings.v.ui.HomeFragment;
import wings.v.ui.SharingFragment;
import wings.v.ui.SettingsFragment;

public class MainPagerAdapter extends FragmentStateAdapter {
    public static final int PAGE_HOME = 0;
    public static final int PAGE_APPS = 1;
    public static final int PAGE_SHARING = 2;
    public static final int PAGE_SETTINGS = 3;
    private static final long ITEM_HOME = 100L;
    private static final long ITEM_APPS = 101L;
    private static final long ITEM_SHARING = 102L;
    private static final long ITEM_SETTINGS = 103L;

    private final boolean hasSharingTab;

    public MainPagerAdapter(@NonNull AppCompatActivity activity, boolean hasSharingTab) {
        super(activity);
        this.hasSharingTab = hasSharingTab;
    }

    public int getPageCount() {
        return hasSharingTab ? 4 : 3;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        long itemId = getItemId(position);
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
        return getPageCount();
    }

    @Override
    public long getItemId(int position) {
        if (position == PAGE_HOME) {
            return ITEM_HOME;
        }
        if (position == PAGE_APPS) {
            return ITEM_APPS;
        }
        if (hasSharingTab && position == PAGE_SHARING) {
            return ITEM_SHARING;
        }
        return ITEM_SETTINGS;
    }

    @Override
    public boolean containsItem(long itemId) {
        if (itemId == ITEM_HOME || itemId == ITEM_APPS || itemId == ITEM_SETTINGS) {
            return true;
        }
        return hasSharingTab && itemId == ITEM_SHARING;
    }
}
