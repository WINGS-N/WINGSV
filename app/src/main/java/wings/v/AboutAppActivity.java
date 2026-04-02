package wings.v;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import dev.oneuiproject.oneui.widget.CardItemView;
import wings.v.core.AvatarDrawableFactory;
import wings.v.core.BrowserLauncher;
import wings.v.core.GithubAvatarLoader;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityAboutAppBinding;

public class AboutAppActivity extends AppCompatActivity {
    private static final String GITHUB_WINGS_N = "WINGS-N";
    private static final String GITHUB_MYGOD = "Mygod";
    private static final String GITHUB_TRIBALFS = "tribalfs";
    private static final String GITHUB_YANNDROID = "Yanndroid";
    private static final String GITHUB_SALVOGIANGRI = "salvogiangri";
    private static final String GITHUB_ZX2C4 = "zx2c4";
    private static final String GITHUB_CACGGGHP = "cacggghp";
    private static final String SAMSUNG_URL = "https://www.samsung.com/";

    private ActivityAboutAppBinding binding;
    private GithubAvatarLoader githubAvatarLoader;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    public static Intent createIntent(Context context) {
        return new Intent(context, AboutAppActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutAppBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        githubAvatarLoader = new GithubAvatarLoader(this);
        connectivityManager = getSystemService(ConnectivityManager.class);
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        bindHeader();
        bindCards();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
        refreshGithubAvatars();
    }

    @Override
    protected void onStop() {
        unregisterNetworkCallback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    private void bindHeader() {
        binding.imageAppIcon.setImageDrawable(loadAppIcon());
        binding.textAppName.setText(R.string.app_name);
        binding.textAppVersion.setText(getString(R.string.about_version_label, loadVersionName()));
    }

    private Drawable loadAppIcon() {
        try {
            return getPackageManager().getApplicationIcon(getPackageName());
        } catch (Exception ignored) {
            return getDrawable(R.mipmap.ic_launcher_round);
        }
    }

    private String loadVersionName() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= 33) {
                packageInfo = getPackageManager().getPackageInfo(
                        getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            if (packageInfo.versionName != null) {
                return packageInfo.versionName;
            }
        } catch (Exception ignored) {
            // No-op.
        }
        return "1.0";
    }

    private void bindCards() {
        configureGithubCard(
                binding.cardDeveloperWingsN,
                GITHUB_WINGS_N,
                "WN",
                Color.parseColor("#2D6BE5"),
                "https://github.com/WINGS-N"
        );

        configureGithubCard(
                binding.cardSpecialTribalfs,
                GITHUB_TRIBALFS,
                "TF",
                Color.parseColor("#1E8E5A"),
                "https://github.com/tribalfs"
        );
        configureGithubCard(
                binding.cardSpecialYanndroid,
                GITHUB_YANNDROID,
                "YN",
                Color.parseColor("#F18A27"),
                "https://github.com/Yanndroid"
        );
        configureGithubCard(
                binding.cardSpecialSalvogiangri,
                GITHUB_SALVOGIANGRI,
                "SG",
                Color.parseColor("#9A5C2F"),
                "https://github.com/salvogiangri"
        );
        configureStaticCard(
                binding.cardSpecialSamsung,
                AvatarDrawableFactory.createCircularBanner(
                        this,
                        getDrawable(R.drawable.samsung_black_wtext),
                        Color.BLACK
                ),
                SAMSUNG_URL
        );
        configureGithubCard(
                binding.cardSpecialMygod,
                GITHUB_MYGOD,
                "MG",
                Color.parseColor("#4D7F53"),
                "https://github.com/Mygod"
        );
        configureGithubCard(
                binding.cardSpecialZx2c4,
                GITHUB_ZX2C4,
                "ZX",
                Color.parseColor("#51657A"),
                "https://github.com/zx2c4"
        );
        configureGithubCard(
                binding.cardSpecialCacggghp,
                GITHUB_CACGGGHP,
                "CC",
                Color.parseColor("#685ACF"),
                "https://github.com/cacggghp"
        );

        binding.cardSourceCode.setOnClickListener(view -> {
            Haptics.softSelection(view);
            BrowserLauncher.open(this, "https://github.com/WINGS-N/WINGSV");
        });
        binding.cardOpenSourceLicenses.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(OpenSourceLicensesActivity.createIntent(this));
        });
    }

    private void configureGithubCard(CardItemView cardItemView,
                                     String username,
                                     String initials,
                                     int backgroundColor,
                                     String url) {
        cardItemView.setTag(username);
        cardItemView.setIcon(resolveGithubAvatar(username, initials, backgroundColor));
        cardItemView.setOnClickListener(view -> {
            Haptics.softSelection(view);
            BrowserLauncher.open(this, url);
        });
    }

    private void configureStaticCard(CardItemView cardItemView,
                                     Drawable icon,
                                     String url) {
        cardItemView.setIcon(icon);
        cardItemView.setOnClickListener(view -> {
            Haptics.softSelection(view);
            BrowserLauncher.open(this, url);
        });
    }

    private Drawable resolveGithubAvatar(String username, String initials, int backgroundColor) {
        Drawable cached = githubAvatarLoader.loadCached(username);
        if (cached != null) {
            return cached;
        }
        return AvatarDrawableFactory.create(this, initials, backgroundColor);
    }

    private void refreshGithubAvatars() {
        refreshGithubAvatar(binding.cardDeveloperWingsN, GITHUB_WINGS_N);
        refreshGithubAvatar(binding.cardSpecialTribalfs, GITHUB_TRIBALFS);
        refreshGithubAvatar(binding.cardSpecialSalvogiangri, GITHUB_SALVOGIANGRI);
        refreshGithubAvatar(binding.cardSpecialMygod, GITHUB_MYGOD);
        refreshGithubAvatar(binding.cardSpecialYanndroid, GITHUB_YANNDROID);
        refreshGithubAvatar(binding.cardSpecialZx2c4, GITHUB_ZX2C4);
        refreshGithubAvatar(binding.cardSpecialCacggghp, GITHUB_CACGGGHP);
    }

    private void refreshGithubAvatar(CardItemView cardItemView, String username) {
        githubAvatarLoader.fetch(username, drawable -> {
            if (binding == null || isFinishing() || isDestroyed()) {
                return;
            }
            Object tag = cardItemView.getTag();
            if (!(tag instanceof String) || !username.equals(tag)) {
                return;
            }
            cardItemView.setIcon(drawable);
        });
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || networkCallback != null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                refreshGithubAvatars();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // No-op.
        }
        networkCallback = null;
    }
}
