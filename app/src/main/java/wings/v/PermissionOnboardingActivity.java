package wings.v;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.PermissionUtils;
import wings.v.core.RootUtils;
import wings.v.databinding.ActivityPermissionOnboardingBinding;
import wings.v.databinding.ItemPermissionStatusBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PermissionOnboardingActivity extends AppCompatActivity {
    private static final String EXTRA_FORCE_SHOW = "extra_force_show";

    private ActivityPermissionOnboardingBinding binding;
    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean rootCheckInProgress;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> refreshRows()
            );

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> refreshRows()
            );

    public static Intent createIntent(Context context, boolean forceShow) {
        return new Intent(context, PermissionOnboardingActivity.class)
                .putExtra(EXTRA_FORCE_SHOW, forceShow);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppPrefs.markOnboardingSeen(this);

        binding = ActivityPermissionOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        configureRow(
                binding.rowNotifications,
                R.string.permission_notifications,
                R.string.permission_notifications_summary,
                v -> requestNotificationsPermission()
        );
        configureRow(
                binding.rowBattery,
                R.string.permission_battery,
                R.string.permission_battery_summary,
                v -> requestBatteryOptimizationExclusion()
        );
        configureRow(
                binding.rowVpn,
                R.string.permission_vpn,
                R.string.permission_vpn_summary,
                v -> requestVpnPermission()
        );
        configureRow(
                binding.rowRoot,
                R.string.permission_root,
                R.string.permission_root_summary,
                v -> requestRootPermission()
        );
        binding.rowRoot.permissionAction.setText(R.string.check_label);

        binding.buttonContinue.setOnClickListener(v -> {
            Haptics.softConfirm(v);
            setResult(RESULT_OK);
            finish();
        });

        refreshRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRows();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rootExecutor.shutdownNow();
    }

    private void configureRow(ItemPermissionStatusBinding rowBinding, int titleRes, int summaryRes,
                              View.OnClickListener listener) {
        rowBinding.permissionTitle.setText(titleRes);
        rowBinding.permissionSummary.setText(summaryRes);
        rowBinding.permissionAction.setOnClickListener(v -> {
            Haptics.softSelection(v);
            listener.onClick(v);
        });
    }

    private void refreshRows() {
        updateRow(
                binding.rowNotifications,
                PermissionUtils.isNotificationGranted(this),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        );
        updateRow(
                binding.rowBattery,
                PermissionUtils.isIgnoringBatteryOptimizations(this),
                true
        );
        updateRow(
                binding.rowVpn,
                PermissionUtils.isVpnPermissionGranted(this),
                true
        );
        updateRow(
                binding.rowRoot,
                PermissionUtils.isRootPermissionGranted(this),
                !rootCheckInProgress
        );
        binding.rowRoot.permissionAction.setText(
                rootCheckInProgress ? R.string.checking_label : R.string.check_label
        );

        binding.buttonContinue.setEnabled(PermissionUtils.areCorePermissionsGranted(this));
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
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent == null) {
            refreshRows();
            return;
        }
        vpnPermissionLauncher.launch(intent);
    }

    private void requestRootPermission() {
        if (rootCheckInProgress) {
            return;
        }
        rootCheckInProgress = true;
        refreshRows();
        rootExecutor.execute(() -> {
            boolean granted = RootUtils.refreshRootAccessState(getApplicationContext());
            runOnUiThread(() -> {
                rootCheckInProgress = false;
                refreshRows();
            });
        });
    }
}
