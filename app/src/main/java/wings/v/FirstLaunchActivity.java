package wings.v;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;
import wings.v.core.AppPrefs;
import wings.v.core.AutoSearchManager;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityFirstLaunchBinding;
import wings.v.ui.FirstLaunchPagerAdapter;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class FirstLaunchActivity
    extends AppCompatActivity
    implements
        FirstLaunchPermissionsFragment.Host,
        FirstLaunchIntroFragment.Host,
        FirstLaunchConnectionFragment.Host,
        FirstLaunchVkTurnFragment.Host,
        FirstLaunchXrayFragment.Host,
        FirstLaunchAutoSearchSettingsFragment.Host,
        FirstLaunchAutoSearchModeFragment.Host,
        FirstLaunchAutoSearchRunFragment.Host,
        FirstLaunchDoneFragment.Host
{

    private static final String EXTRA_START_AT_PERMISSIONS = "extra_start_at_permissions";
    private static final long PAGE_TRANSITION_OUT_MS = 1_000L;
    private static final long PAGE_TRANSITION_IN_MS = 1_000L;
    private static final int PAGE_TRANSITION_OFFSET_DP = 18;
    private static final long ENTRY_FADE_MS = 2_000L;
    private static final long EXIT_FADE_MS = 2_000L;
    private static final long INTRO_MUSIC_FADE_IN_MS = 20_000L;
    private static final float INTRO_MUSIC_MAX_VOLUME = 0.2f;
    private static final int INTRO_MUSIC_OFFSET_MS = 51_500;
    private static final String STATE_INTRO_MUSIC_POSITION_MS = "state_intro_music_position_ms";
    private static final String STATE_INTRO_MUSIC_LOOP_FROM_START = "state_intro_music_loop_from_start";

    private ActivityFirstLaunchBinding binding;
    private float backgroundProgress;
    private boolean pageTransitionRunning;
    private boolean exitTransitionRunning;
    private boolean startAtPermissions;
    private MediaPlayer introMusicPlayer;
    private ValueAnimator introMusicVolumeAnimator;
    private AudioManager audioManager;
    private AudioFocusRequest introAudioFocusRequest;
    private boolean introAudioFocusHeld;
    private int introMusicPositionMs = INTRO_MUSIC_OFFSET_MS;
    private boolean introMusicLoopFromStart;
    private float introMusicCurrentVolume;
    private boolean introVideoStarted;

    @Nullable
    private AutoSearchManager.Mode firstLaunchAutoSearchMode;

    private long firstLaunchAutoSearchGeneration;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pauseIntroMusicInternal();
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && hasWindowFocus()) {
            resumeIntroMusicPlayback();
        }
    };

    public static Intent createIntent(Context context) {
        return new Intent(context, FirstLaunchActivity.class);
    }

    public static Intent createPermissionsIntent(Context context) {
        return new Intent(context, FirstLaunchActivity.class).putExtra(EXTRA_START_AT_PERMISSIONS, true);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        startAtPermissions = getIntent().getBooleanExtra(EXTRA_START_AT_PERMISSIONS, false);

        if (savedInstanceState != null) {
            introMusicPositionMs = savedInstanceState.getInt(STATE_INTRO_MUSIC_POSITION_MS, INTRO_MUSIC_OFFSET_MS);
            introMusicLoopFromStart = savedInstanceState.getBoolean(STATE_INTRO_MUSIC_LOOP_FROM_START, false);
        }

        binding = ActivityFirstLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.contentFirstLaunch.setAlpha(0f);

        applyInsets();
        configurePager();
        configureBackHandling();
        configureButtons();
        updateForPage(0);
        if (startAtPermissions) {
            playEntryFadeIn();
        } else {
            playIntroVideo();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        resumeIntroMusic();
    }

    @Override
    protected void onStop() {
        stopIntroVideo();
        pauseIntroMusic();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releaseIntroMusic();
        binding = null;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (introMusicPlayer != null) {
            introMusicPositionMs = Math.max(introMusicPlayer.getCurrentPosition(), 0);
        }
        outState.putInt(STATE_INTRO_MUSIC_POSITION_MS, introMusicPositionMs);
        outState.putBoolean(STATE_INTRO_MUSIC_LOOP_FROM_START, introMusicLoopFromStart);
    }

    @Override
    public void onOnboardingCompleted() {
        if (exitTransitionRunning) {
            return;
        }
        if (!startAtPermissions && binding != null && binding.firstLaunchPager.getCurrentItem() < getLastPagerIndex()) {
            Haptics.softConfirm(binding.firstLaunchPager);
            animateToPage(binding.firstLaunchPager.getCurrentItem() + 1);
            return;
        }
        completeFirstLaunch();
    }

    @Override
    public void onConnectionChoiceSelected(@NonNull String choice) {
        if (exitTransitionRunning) {
            return;
        }
        if (FirstLaunchConnectionFragment.CHOICE_VK_TURN.equals(choice)) {
            animateToPage(3);
            return;
        }
        if (FirstLaunchConnectionFragment.CHOICE_XRAY.equals(choice)) {
            animateToPage(4);
            return;
        }
        if (FirstLaunchConnectionFragment.CHOICE_AUTO_SEARCH.equals(choice)) {
            animateToPage(5);
            return;
        }
        completeFirstLaunch();
    }

    @Override
    public void onVkTurnSettingsCompleted() {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        animateToPage(getLastPagerIndex());
    }

    @Override
    public void onXraySettingsCompleted() {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        animateToPage(getLastPagerIndex());
    }

    @Override
    public void onAutoSearchSettingsCompleted() {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        animateToPage(6);
    }

    @Override
    public void onFirstLaunchAutoSearchModeSelected(@NonNull AutoSearchManager.Mode mode) {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        firstLaunchAutoSearchMode = mode;
        firstLaunchAutoSearchGeneration++;
        animateToPage(7);
    }

    @Nullable
    @Override
    public AutoSearchManager.Mode getFirstLaunchAutoSearchMode() {
        return firstLaunchAutoSearchMode;
    }

    @Override
    public long getFirstLaunchAutoSearchGeneration() {
        return firstLaunchAutoSearchGeneration;
    }

    @Override
    public boolean isFirstLaunchAutoSearchRunPageActive() {
        return binding != null && binding.firstLaunchPager.getCurrentItem() == 7;
    }

    @Override
    public void onFirstLaunchAutoSearchFinished() {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        animateToPage(getLastPagerIndex());
    }

    @Override
    public void onFirstLaunchAutoSearchBackToMode() {
        if (exitTransitionRunning || binding == null) {
            return;
        }
        animateToPage(6);
    }

    @Override
    public void onFirstLaunchDone() {
        completeFirstLaunch();
    }

    private void completeFirstLaunch() {
        AppPrefs.markOnboardingSeen(this);
        AppPrefs.markFirstLaunchExperienceSeen(this);
        setResult(RESULT_OK);
        animateExitAndFinish();
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.firstLaunchPager.setPadding(0, systemBars.top, 0, systemBars.bottom);
            return insets;
        });
    }

    private void configurePager() {
        FirstLaunchPagerAdapter adapter = new FirstLaunchPagerAdapter(this, startAtPermissions);
        binding.firstLaunchPager.setAdapter(adapter);
        binding.firstLaunchPager.setOffscreenPageLimit(2);
        binding.firstLaunchPager.setUserInputEnabled(false);
        binding.firstLaunchPager.registerOnPageChangeCallback(
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                    if (!pageTransitionRunning) {
                        backgroundProgress = position + positionOffset;
                        binding.backgroundFirstLaunch.setPagerProgress(backgroundProgress);
                    }
                }

                @Override
                public void onPageSelected(int position) {
                    updateForPage(position);
                }
            }
        );
        if (startAtPermissions) {
            binding.firstLaunchPager.setCurrentItem(Math.min(1, adapter.getItemCount() - 1), false);
        }
    }

    private void updateForPage(int page) {
        backgroundProgress = page;
        binding.backgroundFirstLaunch.setPagerProgress(page);
    }

    private void configureButtons() {}

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleFirstLaunchBackPressed();
                }
            }
        );
    }

    private void handleFirstLaunchBackPressed() {
        if (exitTransitionRunning || pageTransitionRunning || binding == null) {
            return;
        }
        if (startAtPermissions) {
            setResult(RESULT_CANCELED);
            finishWithoutAnimation();
            return;
        }
        int page = binding.firstLaunchPager.getCurrentItem();
        if (page == 1) {
            animateToPage(0);
            return;
        }
        if (page == 2) {
            animateToPage(1);
            return;
        }
        if (page == 3 || page == 4 || page == 5) {
            animateToPage(2);
            return;
        }
        if (page == 6) {
            animateToPage(5);
            return;
        }
        if (page == 7) {
            AutoSearchManager.State state = AutoSearchManager.getInstance(this).getState();
            if (
                state.status == AutoSearchManager.Status.AWAITING_MODE_SELECTION ||
                state.status == AutoSearchManager.Status.FAILED ||
                state.status == AutoSearchManager.Status.IDLE
            ) {
                animateToPage(6);
                return;
            }
            Toast.makeText(this, R.string.first_launch_back_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.first_launch_back_blocked, Toast.LENGTH_SHORT).show();
    }

    private void playEntryFadeIn() {
        if (binding == null) {
            return;
        }
        binding.contentFirstLaunch.animate().cancel();
        binding.contentFirstLaunch
            .animate()
            .alpha(1f)
            .setDuration(ENTRY_FADE_MS)
            .setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f))
            .start();
    }

    private void playIntroVideo() {
        if (binding == null || introVideoStarted) {
            return;
        }
        introVideoStarted = true;
        binding.contentFirstLaunch.setAlpha(1f);
        binding.videoFirstLaunchIntro.setAlpha(0f);
        binding.videoFirstLaunchIntro.setZOrderOnTop(false);
        binding.videoFirstLaunchIntro.setZOrderMediaOverlay(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.videoFirstLaunchIntro.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE);
        }
        binding.videoFirstLaunchIntro.setVisibility(View.VISIBLE);
        binding.videoFirstLaunchIntro.setVideoURI(
            Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.suw_intro_in)
        );
        binding.videoFirstLaunchIntro.setOnPreparedListener(player -> {
            player.setVolume(0f, 0f);
            binding.videoFirstLaunchIntro.setVideoSize(player);
            binding.videoFirstLaunchIntro.animate().cancel();
            binding.videoFirstLaunchIntro
                .animate()
                .alpha(1f)
                .setDuration(300L)
                .setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f))
                .start();
            if (binding != null) {
                binding.videoFirstLaunchIntro.start();
            }
        });
        binding.videoFirstLaunchIntro.setOnCompletionListener(player -> finishIntroVideo());
        binding.videoFirstLaunchIntro.setOnErrorListener((player, what, extra) -> {
            finishIntroVideo();
            return true;
        });
        binding.videoFirstLaunchIntro.requestFocus();
    }

    private void finishIntroVideo() {
        if (binding == null) {
            return;
        }
        binding.contentFirstLaunch.setAlpha(1f);
        binding.videoFirstLaunchIntro.animate().cancel();
        binding.videoFirstLaunchIntro.setVisibility(View.GONE);
    }

    private void stopIntroVideo() {
        if (binding == null) {
            return;
        }
        try {
            binding.videoFirstLaunchIntro.stopPlayback();
        } catch (IllegalStateException ignored) {}
    }

    private void animateExitAndFinish() {
        if (binding == null) {
            finishWithoutAnimation();
            return;
        }
        exitTransitionRunning = true;
        View root = binding.getRoot();
        root.animate().cancel();
        root.setAlpha(1f);

        ValueAnimator audioFade = ValueAnimator.ofFloat(introMusicCurrentVolume, 0f);
        cancelIntroMusicVolumeAnimation();
        audioFade.setDuration(EXIT_FADE_MS);
        audioFade.setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f));
        audioFade.addUpdateListener(animation -> {
            setIntroMusicVolume((float) animation.getAnimatedValue());
        });
        audioFade.start();

        root
            .animate()
            .alpha(0f)
            .setDuration(EXIT_FADE_MS)
            .setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f))
            .withEndAction(() -> {
                finishWithoutAnimation();
            })
            .start();
    }

    @SuppressWarnings("deprecation")
    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void resumeIntroMusic() {
        if (!requestIntroAudioFocus()) {
            return;
        }
        resumeIntroMusicPlayback();
    }

    private void resumeIntroMusicPlayback() {
        ensureIntroMusicPlayer();
        if (introMusicPlayer == null) {
            return;
        }
        introMusicPlayer.setLooping(introMusicLoopFromStart);
        int targetPositionMs = introMusicLoopFromStart
            ? Math.max(introMusicPositionMs, 0)
            : Math.max(introMusicPositionMs, INTRO_MUSIC_OFFSET_MS);
        introMusicPlayer.seekTo(targetPositionMs);
        setIntroMusicVolume(0f);
        if (!introMusicPlayer.isPlaying()) {
            introMusicPlayer.start();
        }
        animateIntroMusicVolume(0f, INTRO_MUSIC_MAX_VOLUME, INTRO_MUSIC_FADE_IN_MS);
    }

    private void pauseIntroMusic() {
        pauseIntroMusicInternal();
        abandonIntroAudioFocus();
    }

    private void pauseIntroMusicInternal() {
        if (introMusicPlayer == null) {
            return;
        }
        cancelIntroMusicVolumeAnimation();
        introMusicPositionMs = Math.max(introMusicPlayer.getCurrentPosition(), 0);
        if (introMusicPlayer.isPlaying()) {
            introMusicPlayer.pause();
        }
    }

    private void releaseIntroMusic() {
        cancelIntroMusicVolumeAnimation();
        abandonIntroAudioFocus();
        if (introMusicPlayer == null) {
            return;
        }
        introMusicPlayer.release();
        introMusicPlayer = null;
    }

    private void ensureIntroMusicPlayer() {
        if (introMusicPlayer != null) {
            return;
        }
        introMusicPlayer = MediaPlayer.create(this, R.raw.samsung_tv_over_the_horizon);
        if (introMusicPlayer == null) {
            return;
        }
        introMusicPlayer.setOnCompletionListener(player -> {
            introMusicLoopFromStart = true;
            introMusicPositionMs = 0;
            player.setLooping(true);
            player.seekTo(0);
            player.start();
        });
    }

    private boolean requestIntroAudioFocus() {
        if (introAudioFocusHeld) {
            return true;
        }
        if (audioManager == null) {
            audioManager = getSystemService(AudioManager.class);
        }
        if (audioManager == null) {
            return true;
        }
        if (introAudioFocusRequest == null) {
            introAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
        }
        int result = audioManager.requestAudioFocus(introAudioFocusRequest);
        introAudioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return introAudioFocusHeld;
    }

    private void abandonIntroAudioFocus() {
        if (!introAudioFocusHeld) {
            return;
        }
        if (audioManager != null && introAudioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(introAudioFocusRequest);
        }
        introAudioFocusHeld = false;
    }

    private void animateIntroMusicVolume(float from, float to, long durationMs) {
        if (introMusicPlayer == null) {
            return;
        }
        cancelIntroMusicVolumeAnimation();
        introMusicVolumeAnimator = ValueAnimator.ofFloat(from, to);
        introMusicVolumeAnimator.setDuration(durationMs);
        introMusicVolumeAnimator.setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f));
        introMusicVolumeAnimator.addUpdateListener(animation -> {
            setIntroMusicVolume((float) animation.getAnimatedValue());
        });
        introMusicVolumeAnimator.start();
    }

    private void setIntroMusicVolume(float volume) {
        introMusicCurrentVolume = Math.max(0f, Math.min(volume, INTRO_MUSIC_MAX_VOLUME));
        if (introMusicPlayer != null) {
            introMusicPlayer.setVolume(introMusicCurrentVolume, introMusicCurrentVolume);
        }
    }

    private void cancelIntroMusicVolumeAnimation() {
        if (introMusicVolumeAnimator == null) {
            return;
        }
        introMusicVolumeAnimator.cancel();
        introMusicVolumeAnimator = null;
    }

    @Override
    public void onAdvanceIntroPage() {
        int currentPage = binding.firstLaunchPager.getCurrentItem();
        if (currentPage >= getLastPagerIndex() || pageTransitionRunning) {
            return;
        }
        Haptics.softConfirm(binding.firstLaunchPager);
        animateToPage(currentPage + 1);
    }

    private void animateToPage(int targetPage) {
        if (binding == null || pageTransitionRunning) {
            return;
        }
        pageTransitionRunning = true;
        View pager = binding.firstLaunchPager;
        PathInterpolator interpolator = new PathInterpolator(0.22f, 0.25f, 0f, 1f);
        pager
            .animate()
            .alpha(0f)
            .translationY(-dp(PAGE_TRANSITION_OFFSET_DP))
            .setDuration(PAGE_TRANSITION_OUT_MS)
            .setInterpolator(interpolator)
            .withEndAction(() -> {
                binding.firstLaunchPager.setCurrentItem(targetPage, false);
                pager.setTranslationY(-dp(PAGE_TRANSITION_OFFSET_DP));
                animateBackgroundProgress(backgroundProgress, targetPage);
                pager
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(PAGE_TRANSITION_IN_MS)
                    .setInterpolator(interpolator)
                    .withEndAction(() -> pageTransitionRunning = false)
                    .start();
            })
            .start();
    }

    private int getLastPagerIndex() {
        if (binding == null || binding.firstLaunchPager.getAdapter() == null) {
            return 0;
        }
        return Math.max(binding.firstLaunchPager.getAdapter().getItemCount() - 1, 0);
    }

    private void animateBackgroundProgress(float from, float to) {
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(PAGE_TRANSITION_OUT_MS + PAGE_TRANSITION_IN_MS);
        animator.setInterpolator(new PathInterpolator(0.22f, 0.25f, 0f, 1f));
        animator.addUpdateListener(animation -> {
            backgroundProgress = (float) animation.getAnimatedValue();
            binding.backgroundFirstLaunch.setPagerProgress(backgroundProgress);
        });
        animator.start();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
