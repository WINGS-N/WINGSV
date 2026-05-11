package wings.v.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import wings.v.FirstLaunchAutoSearchModeFragment;
import wings.v.FirstLaunchAutoSearchRunFragment;
import wings.v.FirstLaunchAutoSearchSettingsFragment;
import wings.v.FirstLaunchConnectionFragment;
import wings.v.FirstLaunchDoneFragment;
import wings.v.FirstLaunchIntroFragment;
import wings.v.FirstLaunchPermissionsFragment;
import wings.v.FirstLaunchVkTurnFragment;
import wings.v.FirstLaunchXrayFragment;
import wings.v.R;

public class FirstLaunchPagerAdapter extends FragmentStateAdapter {

    private final boolean permissionsOnlyMode;

    public FirstLaunchPagerAdapter(@NonNull FragmentActivity fragmentActivity, boolean permissionsOnlyMode) {
        super(fragmentActivity);
        this.permissionsOnlyMode = permissionsOnlyMode;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return FirstLaunchIntroFragment.create(
                R.string.first_launch_page_welcome_title,
                R.string.first_launch_page_welcome_subtitle,
                R.string.first_launch_start
            );
        }
        if (position == 1) {
            return FirstLaunchPermissionsFragment.create(R.string.first_launch_next);
        }
        if (position == 2) {
            return FirstLaunchConnectionFragment.create();
        }
        if (position == 3) {
            return FirstLaunchVkTurnFragment.create();
        }
        if (position == 4) {
            return FirstLaunchXrayFragment.create();
        }
        if (position == 5) {
            return FirstLaunchAutoSearchSettingsFragment.create();
        }
        if (position == 6) {
            return FirstLaunchAutoSearchModeFragment.create();
        }
        if (position == 7) {
            return FirstLaunchAutoSearchRunFragment.create();
        }
        return FirstLaunchDoneFragment.create();
    }

    @Override
    public int getItemCount() {
        return permissionsOnlyMode ? 2 : 9;
    }
}
