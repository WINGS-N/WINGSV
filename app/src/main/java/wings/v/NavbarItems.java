package wings.v;

import android.content.Context;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wings.v.core.UiPrefs;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public final class NavbarItems {

    public static final class Item {

        public final String key;
        public final long pagerItemId;
        public final int menuItemId;
        public final int menuResId;

        Item(String key, long pagerItemId, int menuItemId, int menuResId) {
            this.key = key;
            this.pagerItemId = pagerItemId;
            this.menuItemId = menuItemId;
            this.menuResId = menuResId;
        }
    }

    private NavbarItems() {}

    @MenuRes
    public static int menuResForKey(String key) {
        if (UiPrefs.NAVBAR_PROFILES.equals(key)) {
            return R.menu.menu_bottom_tab_profiles;
        }
        if (UiPrefs.NAVBAR_APPS.equals(key)) {
            return R.menu.menu_bottom_tab_apps;
        }
        if (UiPrefs.NAVBAR_SHARING.equals(key)) {
            return R.menu.menu_bottom_tab_sharing;
        }
        if (UiPrefs.NAVBAR_SETTINGS.equals(key)) {
            return R.menu.menu_bottom_tab_settings;
        }
        return R.menu.menu_bottom_tab_home;
    }

    public static int menuItemIdForKey(String key) {
        if (UiPrefs.NAVBAR_PROFILES.equals(key)) {
            return R.id.menu_profiles;
        }
        if (UiPrefs.NAVBAR_APPS.equals(key)) {
            return R.id.menu_apps;
        }
        if (UiPrefs.NAVBAR_SHARING.equals(key)) {
            return R.id.menu_sharing;
        }
        if (UiPrefs.NAVBAR_SETTINGS.equals(key)) {
            return R.id.menu_settings;
        }
        return R.id.menu_home;
    }

    public static long pagerItemIdForKey(String key) {
        if (UiPrefs.NAVBAR_PROFILES.equals(key)) {
            return MainPagerAdapter.ITEM_PROFILES;
        }
        if (UiPrefs.NAVBAR_APPS.equals(key)) {
            return MainPagerAdapter.ITEM_APPS;
        }
        if (UiPrefs.NAVBAR_SHARING.equals(key)) {
            return MainPagerAdapter.ITEM_SHARING;
        }
        if (UiPrefs.NAVBAR_SETTINGS.equals(key)) {
            return MainPagerAdapter.ITEM_SETTINGS;
        }
        return MainPagerAdapter.ITEM_HOME;
    }

    public static String keyForMenuItemId(int menuItemId) {
        if (menuItemId == R.id.menu_profiles) {
            return UiPrefs.NAVBAR_PROFILES;
        }
        if (menuItemId == R.id.menu_apps) {
            return UiPrefs.NAVBAR_APPS;
        }
        if (menuItemId == R.id.menu_sharing) {
            return UiPrefs.NAVBAR_SHARING;
        }
        if (menuItemId == R.id.menu_settings) {
            return UiPrefs.NAVBAR_SETTINGS;
        }
        return UiPrefs.NAVBAR_HOME;
    }

    public static List<Item> resolveVisibleItems(
        @NonNull Context context,
        boolean hasProfilesTab,
        boolean hasSharingTab
    ) {
        List<String> order = UiPrefs.getNavbarOrder(context);
        Set<String> hidden = UiPrefs.getNavbarHidden(context);
        List<Item> resolved = new ArrayList<>(order.size());
        for (String key : order) {
            if (hidden.contains(key) && !UiPrefs.NAVBAR_FORCED_VISIBLE.contains(key)) {
                continue;
            }
            if (UiPrefs.NAVBAR_PROFILES.equals(key) && !hasProfilesTab) {
                continue;
            }
            if (UiPrefs.NAVBAR_SHARING.equals(key) && !hasSharingTab) {
                continue;
            }
            resolved.add(new Item(key, pagerItemIdForKey(key), menuItemIdForKey(key), menuResForKey(key)));
        }
        return resolved;
    }
}
