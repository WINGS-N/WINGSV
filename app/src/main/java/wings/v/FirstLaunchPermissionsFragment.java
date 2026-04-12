package wings.v;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.databinding.FragmentFirstLaunchPermissionsBinding;
import wings.v.databinding.ItemPermissionStatusBinding;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.ShortClassName",
        "PMD.OnlyOneReturn",
        "PMD.ImplicitFunctionalInterface",
    }
)
public class FirstLaunchPermissionsFragment extends Fragment {

    private static final String ARG_BUTTON_RES = "button_res";
    private static final long CONTINUE_BUTTON_STATE_ANIMATION_MS = 1_000L;
    private static final int CONTINUE_BUTTON_ACTIVE_BG = 0xCCFAFAFF;
    private static final int CONTINUE_BUTTON_DISABLED_BG = 0x7AD7E1EB;
    private static final int CONTINUE_BUTTON_ACTIVE_TEXT = 0xFF010102;
    private static final int CONTINUE_BUTTON_DISABLED_TEXT = 0x8A14314A;

    public interface Host {
        void onOnboardingCompleted();
    }

    @Nullable
    private FragmentFirstLaunchPermissionsBinding binding;

    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean rootCheckInProgress;
    private boolean batteryOptimizationRequestPending;

    @Nullable
    private Boolean continueButtonEnabledState;

    @Nullable
    private ValueAnimator continueButtonStateAnimator;

    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        granted -> refreshRows()
    );

    private final ActivityResultLauncher<Intent> batteryOptimizationLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            completeBatteryOptimizationRequestIfNeeded();
            refreshRows();
        }
    );

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            refreshRows();
            if (isAdded() && !PermissionUtils.isVpnPermissionGranted(requireContext())) {
                openVpnSettingsForAlwaysOn();
            }
        }
    );

    public static FirstLaunchPermissionsFragment create(int buttonRes) {
        FirstLaunchPermissionsFragment fragment = new FirstLaunchPermissionsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_BUTTON_RES, buttonRes);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchPermissionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        configureRow(
            binding.rowNotifications,
            R.string.permission_notifications,
            R.string.permission_notifications_summary,
            v -> requestNotificationsPermission()
        );
        configureRow(binding.rowBattery, R.string.permission_battery, R.string.permission_battery_summary, v ->
            requestBatteryOptimizationExclusion()
        );
        configureRow(binding.rowVpn, R.string.permission_vpn, R.string.permission_vpn_summary, v ->
            requestVpnPermission()
        );
        configureRow(binding.rowRoot, R.string.permission_root, R.string.permission_root_summary, v ->
            requestRootPermission()
        );
        binding.rowRoot.permissionAction.setText(R.string.check_label);
        int buttonRes =
            requireArguments() != null
                ? requireArguments().getInt(ARG_BUTTON_RES, R.string.first_launch_continue)
                : R.string.first_launch_continue;
        binding.buttonFirstLaunchContinue.setText(buttonRes);
        binding.buttonFirstLaunchContinue.setOnClickListener(v -> {
            Haptics.softConfirm(v);
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onOnboardingCompleted();
            }
        });
        refreshRows();
    }

    @Override
    public void onResume() {
        super.onResume();
        completeBatteryOptimizationRequestIfNeeded();
        refreshRows();
    }

    @Override
    public void onDestroyView() {
        cancelContinueButtonStateAnimation();
        continueButtonEnabledState = null;
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        rootExecutor.shutdownNow();
        super.onDestroy();
    }

    private void configureRow(
        ItemPermissionStatusBinding rowBinding,
        int titleRes,
        int summaryRes,
        View.OnClickListener listener
    ) {
        rowBinding.permissionTitle.setText(titleRes);
        rowBinding.permissionSummary.setText(summaryRes);
        rowBinding.permissionAction.setOnClickListener(v -> {
            Haptics.softSelection(v);
            listener.onClick(v);
        });
    }

    private void refreshRows() {
        if (binding == null || !isAdded()) {
            return;
        }
        updateRow(
            binding.rowNotifications,
            PermissionUtils.isNotificationGranted(requireContext()),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        );
        updateRow(binding.rowBattery, PermissionUtils.isIgnoringBatteryOptimizations(requireContext()), true);
        updateRow(binding.rowVpn, PermissionUtils.isVpnPermissionGranted(requireContext()), true);
        updateRow(binding.rowRoot, PermissionUtils.isRootPermissionGranted(requireContext()), !rootCheckInProgress);
        binding.rowRoot.permissionAction.setText(rootCheckInProgress ? R.string.checking_label : R.string.check_label);
        updateContinueButtonState(PermissionUtils.areCorePermissionsGranted(requireContext()));
    }

    private void updateRow(ItemPermissionStatusBinding rowBinding, boolean granted, boolean actionable) {
        rowBinding.permissionStatusIcon.setImageResource(
            granted ? R.drawable.ic_check_circle : R.drawable.ic_close_circle
        );
        rowBinding.permissionAction.setVisibility(!granted && actionable ? View.VISIBLE : View.GONE);
    }

    private void requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void requestBatteryOptimizationExclusion() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
            Uri.parse("package:" + requireContext().getPackageName())
        );
        if (launchBatteryOptimizationIntent(intent)) {
            return;
        }
        launchBatteryOptimizationIntent(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private boolean launchBatteryOptimizationIntent(Intent intent) {
        try {
            batteryOptimizationRequestPending = true;
            batteryOptimizationLauncher.launch(intent);
            return true;
        } catch (RuntimeException ignored) {
            batteryOptimizationRequestPending = false;
            return false;
        }
    }

    private void completeBatteryOptimizationRequestIfNeeded() {
        if (!batteryOptimizationRequestPending || !isAdded()) {
            return;
        }
        batteryOptimizationRequestPending = false;
        AppPrefs.setBatteryOptimizationAcknowledged(
            requireContext(),
            !PermissionUtils.isIgnoringBatteryOptimizationsSystem(requireContext())
        );
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void requestVpnPermission() {
        Intent intent;
        try {
            intent = VpnService.prepare(requireContext());
        } catch (RuntimeException e) {
            openVpnSettingsForAlwaysOn();
            return;
        }
        if (intent == null) {
            refreshRows();
            return;
        }
        try {
            vpnPermissionLauncher.launch(intent);
        } catch (RuntimeException e) {
            openVpnSettingsForAlwaysOn();
        }
    }

    private void openVpnSettingsForAlwaysOn() {
        if (!isAdded()) {
            return;
        }
        Toast.makeText(requireContext(), R.string.permission_vpn_always_on_hint, Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void requestRootPermission() {
        if (rootCheckInProgress) {
            return;
        }
        rootCheckInProgress = true;
        refreshRows();
        final android.content.Context appContext = requireContext().getApplicationContext();
        rootExecutor.execute(() -> {
            RootUtils.refreshRootAccessState(appContext);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                rootCheckInProgress = false;
                refreshRows();
            });
        });
    }

    private void updateContinueButtonState(boolean enabled) {
        if (binding == null) {
            return;
        }
        boolean animate = continueButtonEnabledState != null && continueButtonEnabledState != enabled;
        continueButtonEnabledState = enabled;
        binding.buttonFirstLaunchContinue.setEnabled(enabled);
        if (animate) {
            animateContinueButtonState(enabled);
        } else {
            applyContinueButtonColors(
                enabled ? CONTINUE_BUTTON_ACTIVE_BG : CONTINUE_BUTTON_DISABLED_BG,
                enabled ? CONTINUE_BUTTON_ACTIVE_TEXT : CONTINUE_BUTTON_DISABLED_TEXT
            );
        }
    }

    private void animateContinueButtonState(boolean enabled) {
        if (binding == null) {
            return;
        }
        cancelContinueButtonStateAnimation();
        final int fromBackground = enabled ? CONTINUE_BUTTON_DISABLED_BG : CONTINUE_BUTTON_ACTIVE_BG;
        final int toBackground = enabled ? CONTINUE_BUTTON_ACTIVE_BG : CONTINUE_BUTTON_DISABLED_BG;
        final int fromText = enabled ? CONTINUE_BUTTON_DISABLED_TEXT : CONTINUE_BUTTON_ACTIVE_TEXT;
        final int toText = enabled ? CONTINUE_BUTTON_ACTIVE_TEXT : CONTINUE_BUTTON_DISABLED_TEXT;
        final ArgbEvaluator evaluator = new ArgbEvaluator();
        continueButtonStateAnimator = ValueAnimator.ofFloat(0f, 1f);
        continueButtonStateAnimator.setDuration(CONTINUE_BUTTON_STATE_ANIMATION_MS);
        continueButtonStateAnimator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            int background = (int) evaluator.evaluate(fraction, fromBackground, toBackground);
            int text = (int) evaluator.evaluate(fraction, fromText, toText);
            applyContinueButtonColors(background, text);
        });
        continueButtonStateAnimator.start();
    }

    private void applyContinueButtonColors(int backgroundColor, int textColor) {
        if (binding == null) {
            return;
        }
        binding.buttonFirstLaunchContinue.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        binding.buttonFirstLaunchContinue.setTextColor(textColor);
    }

    private void cancelContinueButtonStateAnimation() {
        if (continueButtonStateAnimator == null) {
            return;
        }
        continueButtonStateAnimator.cancel();
        continueButtonStateAnimator = null;
    }
}
