package wings.v;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.io.File;
import wings.v.core.AppUpdateManager;
import wings.v.core.AvatarDrawableFactory;
import wings.v.core.BrowserLauncher;
import wings.v.core.GithubAvatarLoader;
import wings.v.core.Haptics;
import wings.v.core.UiFormatter;
import wings.v.databinding.ActivityAboutAppBinding;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.ExcessiveImports",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
        "PMD.TooManyMethods",
        "PMD.NcssCount",
        "PMD.CognitiveComplexity",
        "PMD.NPathComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.ConfusingTernary",
        "PMD.SimplifyBooleanReturns",
    }
)
public class AboutAppActivity extends AppCompatActivity {

    private static final String GITHUB_WINGS_N = "WINGS-N";
    private static final String GITHUB_MYGOD = "Mygod";
    private static final String GITHUB_TRIBALFS = "tribalfs";
    private static final String GITHUB_YANNDROID = "Yanndroid";
    private static final String GITHUB_SALVOGIANGRI = "salvogiangri";
    private static final String GITHUB_ZX2C4 = "zx2c4";
    private static final String GITHUB_CACGGGHP = "cacggghp";
    private static final String GITHUB_XTLS = "XTLS";
    private static final String GITHUB_AMNEZIA_VPN = "amnezia-vpn";
    private static final String GITHUB_MOROKA8 = "Moroka8";
    private static final String SAMSUNG_URL = "https://www.samsung.com/";
    private static final int FIRST_LAUNCH_TRIGGER_TAPS = 5;
    private static final long FIRST_LAUNCH_TRIGGER_WINDOW_MS = 2_000L;

    private ActivityAboutAppBinding binding;
    private GithubAvatarLoader githubAvatarLoader;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AppUpdateManager appUpdateManager;
    private String pendingInstallFilePath = "";
    private int firstLaunchTriggerTapCount;
    private long firstLaunchTriggerStartedAtMs;

    private final AppUpdateManager.Listener updateStateListener = this::renderUpdateState;
    private final ActivityResultLauncher<Intent> unknownSourcesLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (TextUtils.isEmpty(pendingInstallFilePath)) {
                return;
            }
            File downloadedFile = new File(pendingInstallFilePath);
            pendingInstallFilePath = "";
            if (canInstallUnknownApps()) {
                launchInstaller(downloadedFile);
                return;
            }
            Toast.makeText(this, R.string.about_updates_permission_denied, Toast.LENGTH_SHORT).show();
        }
    );

    public static Intent createIntent(Context context) {
        return new Intent(context, AboutAppActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutAppBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        githubAvatarLoader = new GithubAvatarLoader(this);
        appUpdateManager = AppUpdateManager.getInstance(this);
        connectivityManager = getSystemService(ConnectivityManager.class);
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        bindHeader();
        bindCards();
        bindUpdateSection();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
        refreshGithubAvatars();
        appUpdateManager.registerListener(updateStateListener);
        AppUpdateManager.UpdateState currentUpdateState = appUpdateManager.getState();
        renderUpdateState(currentUpdateState);
        if (currentUpdateState.status == AppUpdateManager.Status.IDLE) {
            appUpdateManager.checkForUpdates();
        }
        maybeResumePendingInstall();
    }

    @Override
    protected void onStop() {
        unregisterNetworkCallback();
        appUpdateManager.unregisterListener(updateStateListener);
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
        binding.cardAppHeader.setOnClickListener(view -> handleFirstLaunchTriggerTap(view));
    }

    private void handleFirstLaunchTriggerTap(View view) {
        long now = SystemClock.elapsedRealtime();
        if (now - firstLaunchTriggerStartedAtMs > FIRST_LAUNCH_TRIGGER_WINDOW_MS) {
            firstLaunchTriggerTapCount = 0;
        }
        firstLaunchTriggerTapCount++;
        if (firstLaunchTriggerTapCount == 1) {
            firstLaunchTriggerStartedAtMs = now;
        }
        Haptics.softSelection(view);
        if (firstLaunchTriggerTapCount < FIRST_LAUNCH_TRIGGER_TAPS) {
            return;
        }
        firstLaunchTriggerTapCount = 0;
        startActivity(FirstLaunchActivity.createIntent(this));
    }

    private Drawable loadAppIcon() {
        try {
            return getPackageManager().getApplicationIcon(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
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
        } catch (PackageManager.NameNotFoundException ignored) {
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
            AvatarDrawableFactory.createCircularBanner(this, getDrawable(R.drawable.samsung_black_wtext), Color.BLACK),
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
        configureGithubCard(
            binding.cardSpecialXtls,
            GITHUB_XTLS,
            "XT",
            Color.parseColor("#2F7DBB"),
            "https://github.com/XTLS"
        );
        configureGithubCard(
            binding.cardSpecialAmnezia,
            GITHUB_AMNEZIA_VPN,
            "AW",
            Color.parseColor("#1B8B73"),
            "https://github.com/amnezia-vpn"
        );
        configureGithubCard(
            binding.cardSpecialMoroka8,
            GITHUB_MOROKA8,
            "M8",
            Color.parseColor("#8E5A2B"),
            "https://github.com/Moroka8"
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

    private void bindUpdateSection() {
        binding.cardAppUpdateAction.setOnClickListener(view -> {
            Haptics.softSelection(view);
            handleUpdateAction(appUpdateManager.getState());
        });
        binding.buttonCancelUpdateDownload.setOnClickListener(view -> {
            Haptics.softSelection(view);
            handleUpdateProgressAction();
        });
    }

    private void handleUpdateProgressAction() {
        AppUpdateManager.UpdateState state = appUpdateManager.getState();
        if (state == null) {
            return;
        }
        if (state.status == AppUpdateManager.Status.DOWNLOADING) {
            appUpdateManager.cancelDownload();
            return;
        }
        if (state.status == AppUpdateManager.Status.DOWNLOADED && state.downloadedFile != null) {
            beginInstallFlow(state.downloadedFile);
        }
    }

    private void handleUpdateAction(AppUpdateManager.UpdateState state) {
        if (state == null) {
            appUpdateManager.checkForUpdates();
            return;
        }
        if (state.status == AppUpdateManager.Status.CHECKING || state.status == AppUpdateManager.Status.DOWNLOADING) {
            return;
        }
        if (state.status == AppUpdateManager.Status.DOWNLOADED && state.downloadedFile != null) {
            beginInstallFlow(state.downloadedFile);
            return;
        }
        if (
            state.status == AppUpdateManager.Status.UPDATE_AVAILABLE ||
            (state.status == AppUpdateManager.Status.ERROR && state.releaseInfo != null)
        ) {
            appUpdateManager.startDownload();
            return;
        }
        appUpdateManager.checkForUpdates();
    }

    private void renderUpdateState(AppUpdateManager.UpdateState state) {
        if (binding == null || state == null) {
            return;
        }
        String title;
        String summary;
        boolean checking = state.status == AppUpdateManager.Status.CHECKING;
        boolean downloading = state.status == AppUpdateManager.Status.DOWNLOADING;

        switch (state.status) {
            case CHECKING:
                title = getString(R.string.about_updates_checking_title);
                summary = getString(R.string.about_updates_checking_summary);
                break;
            case UP_TO_DATE:
                title = getString(R.string.about_updates_up_to_date_title);
                summary = getString(
                    R.string.about_updates_up_to_date_summary,
                    safeVersion(state.releaseInfo, loadVersionName())
                );
                break;
            case UPDATE_AVAILABLE:
                title = getString(R.string.about_updates_available_title, safeVersion(state.releaseInfo, ""));
                summary = !TextUtils.isEmpty(state.message)
                    ? state.message
                    : getString(R.string.about_updates_available_summary);
                break;
            case DOWNLOADING:
                title = getString(R.string.about_updates_downloading_title, safeVersion(state.releaseInfo, ""));
                summary = getString(R.string.about_updates_downloading_summary);
                break;
            case DOWNLOADED:
                title = getString(R.string.about_updates_downloaded_title, safeVersion(state.releaseInfo, ""));
                summary = getString(R.string.about_updates_downloaded_summary);
                break;
            case ERROR:
                title = getString(R.string.about_updates_error_title);
                summary = getString(
                    R.string.about_updates_error_summary,
                    TextUtils.isEmpty(state.message) ? getString(R.string.about_updates_check_summary) : state.message
                );
                break;
            case IDLE:
            default:
                title = getString(R.string.about_updates_check_title);
                summary = getString(R.string.about_updates_check_summary);
                break;
        }

        binding.cardAppUpdateAction.setTitle(title);
        binding.cardAppUpdateAction.setSummary(summary);
        binding.cardAppUpdateAction.setEnabled(!checking && !downloading);
        binding.progressUpdateCheck.setVisibility(checking || downloading ? View.VISIBLE : View.GONE);
        boolean showProgress = downloading || state.status == AppUpdateManager.Status.DOWNLOADED;
        binding.layoutUpdateDownloadProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);

        if (showProgress) {
            long downloadedBytes = Math.max(0L, state.downloadedBytes);
            long totalBytes = Math.max(0L, state.totalBytes);
            binding.textUpdateProgressSize.setText(
                totalBytes > 0L
                    ? getString(
                          R.string.about_updates_download_size,
                          UiFormatter.formatBytes(this, downloadedBytes),
                          UiFormatter.formatBytes(this, totalBytes)
                      )
                    : getString(
                          R.string.about_updates_download_size_unknown,
                          UiFormatter.formatBytes(this, downloadedBytes)
                      )
            );
            if (state.status == AppUpdateManager.Status.DOWNLOADED) {
                binding.textUpdateProgressSpeed.setText(R.string.about_updates_download_ready);
                binding.textUpdateProgressRemaining.setText(R.string.about_updates_download_ready);
                binding.progressUpdateDownload.setIndeterminate(false);
                binding.progressUpdateDownload.setProgress(100);
                configureProgressActionButton(
                    binding.buttonCancelUpdateDownload,
                    R.drawable.ic_arrow_down,
                    R.string.about_updates_install_downloaded
                );
            } else {
                binding.textUpdateProgressSpeed.setText(
                    getString(
                        R.string.about_updates_download_speed,
                        UiFormatter.formatBytesPerSecond(this, Math.max(0L, state.speedBytesPerSecond))
                    )
                );
                binding.textUpdateProgressRemaining.setText(
                    state.remainingBytes > 0L
                        ? getString(
                              R.string.about_updates_download_remaining,
                              UiFormatter.formatBytes(this, state.remainingBytes)
                          )
                        : getString(R.string.about_updates_download_remaining_unknown)
                );
                binding.progressUpdateDownload.setIndeterminate(totalBytes <= 0L);
                if (totalBytes > 0L) {
                    binding.progressUpdateDownload.setProgress(Math.max(0, Math.min(100, state.progressPercent)));
                }
                configureProgressActionButton(
                    binding.buttonCancelUpdateDownload,
                    R.drawable.ic_close_circle,
                    R.string.about_updates_cancel_download
                );
            }
        }
    }

    private void beginInstallFlow(File downloadedFile) {
        if (downloadedFile == null || !downloadedFile.isFile()) {
            Toast.makeText(this, R.string.about_updates_install_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canInstallUnknownApps()) {
            pendingInstallFilePath = downloadedFile.getAbsolutePath();
            Toast.makeText(this, R.string.about_updates_install_permission_hint, Toast.LENGTH_SHORT).show();
            Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())
            );
            unknownSourcesLauncher.launch(settingsIntent);
            return;
        }
        pendingInstallFilePath = "";
        launchInstaller(downloadedFile);
    }

    private boolean canInstallUnknownApps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return getPackageManager().canRequestPackageInstalls();
    }

    private void maybeResumePendingInstall() {
        if (TextUtils.isEmpty(pendingInstallFilePath) || !canInstallUnknownApps()) {
            return;
        }
        File pendingFile = new File(pendingInstallFilePath);
        pendingInstallFilePath = "";
        launchInstaller(pendingFile);
    }

    private void launchInstaller(File downloadedFile) {
        if (downloadedFile == null || !downloadedFile.isFile()) {
            Toast.makeText(this, R.string.about_updates_install_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", downloadedFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, AppUpdateManager.getApkMimeType())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(installIntent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.about_updates_install_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void configureGithubCard(
        CardItemView cardItemView,
        String username,
        String initials,
        int backgroundColor,
        String url
    ) {
        cardItemView.setTag(username);
        cardItemView.setIcon(resolveGithubAvatar(username, initials, backgroundColor));
        cardItemView.setOnClickListener(view -> {
            Haptics.softSelection(view);
            BrowserLauncher.open(this, url);
        });
    }

    private void configureStaticCard(CardItemView cardItemView, Drawable icon, String url) {
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
        refreshGithubAvatar(binding.cardSpecialXtls, GITHUB_XTLS);
        refreshGithubAvatar(binding.cardSpecialAmnezia, GITHUB_AMNEZIA_VPN);
        refreshGithubAvatar(binding.cardSpecialMoroka8, GITHUB_MOROKA8);
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
        } catch (SecurityException ignored) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException ignored) {
            // No-op.
        }
        networkCallback = null;
    }

    private String safeVersion(AppUpdateManager.ReleaseInfo releaseInfo, String fallback) {
        if (releaseInfo == null || TextUtils.isEmpty(releaseInfo.versionName)) {
            return fallback;
        }
        return releaseInfo.versionName;
    }

    private void configureProgressActionButton(ImageButton button, int iconResId, int contentDescriptionResId) {
        button.setImageResource(iconResId);
        button.setContentDescription(getString(contentDescriptionResId));
    }
}
