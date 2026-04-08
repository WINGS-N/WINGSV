package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import wings.v.byedpi.ByeDpiLocalRunner;
import wings.v.byedpi.ByeDpiSiteChecker;
import wings.v.byedpi.ByeDpiStrategyResult;
import wings.v.byedpi.ByeDpiStrategyResultAdapter;
import wings.v.core.ByeDpiSettings;
import wings.v.core.ByeDpiStore;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityByeDpiStrategyTestBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.NullAssignment",
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.CognitiveComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
        "PMD.AvoidInstantiatingObjectsInLoops",
    }
)
public class ByeDpiStrategyTestActivity extends AppCompatActivity {

    private static final long RUNNER_START_TIMEOUT_MS = 4_000L;

    private ActivityByeDpiStrategyTestBinding binding;
    private ByeDpiStrategyResultAdapter adapter;
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private Future<?> testTask;
    private volatile boolean testing;

    public static Intent createIntent(Context context) {
        return new Intent(context, ByeDpiStrategyTestActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityByeDpiStrategyTestBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        adapter = new ByeDpiStrategyResultAdapter(result -> {
            Haptics.softSelection(binding.recyclerStrategies);
            ByeDpiStore.applyStrategy(getApplicationContext(), result.command);
            Toast.makeText(this, R.string.byedpi_strategy_applied, Toast.LENGTH_SHORT).show();
        });
        binding.recyclerStrategies.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerStrategies.setAdapter(adapter);

        binding.buttonStartStopTest.setOnClickListener(view -> {
            Haptics.softSelection(view);
            if (testing) {
                stopTesting();
            } else {
                startTesting();
            }
        });

        renderIdleState();
    }

    @Override
    protected void onDestroy() {
        stopTesting();
        testExecutor.shutdownNow();
        super.onDestroy();
    }

    private void startTesting() {
        if (ProxyTunnelService.isActive()) {
            Toast.makeText(this, R.string.byedpi_proxytest_stop_tunnel_first, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> targets = ByeDpiStore.getProxyTestTargets(this);
        List<String> strategies = ByeDpiStore.getProxyTestStrategies(this);
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.byedpi_proxytest_targets_empty, Toast.LENGTH_LONG).show();
            return;
        }
        if (strategies.isEmpty()) {
            Toast.makeText(this, R.string.byedpi_strategy_list_empty, Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<ByeDpiStrategyResult> results = new ArrayList<>();
        for (String strategy : strategies) {
            results.add(new ByeDpiStrategyResult(strategy));
        }

        testing = true;
        adapter.setTesting(true);
        adapter.replaceItems(results);
        binding.textStatus.setText(getString(R.string.byedpi_proxytest_running));
        binding.buttonStartStopTest.setText(R.string.byedpi_proxytest_stop);
        binding.progressIndicator.setVisibility(View.VISIBLE);

        testTask = testExecutor.submit(() -> {
            for (int index = 0; index < results.size(); index++) {
                if (Thread.currentThread().isInterrupted() || !testing) {
                    break;
                }
                ByeDpiStrategyResult result = results.get(index);
                ByeDpiSettings strategySettings = ByeDpiStore.getSettings(getApplicationContext());
                strategySettings.useCommandLineSettings = true;
                strategySettings.rawCommandArgs = result.command;
                int totalRequests = Math.max(1, strategySettings.proxyTestRequests) * targets.size();
                result.totalRequests = totalRequests;

                try (ByeDpiLocalRunner runner = new ByeDpiLocalRunner()) {
                    runner.start(strategySettings, null, RUNNER_START_TIMEOUT_MS);
                    int successCount = ByeDpiSiteChecker.countSuccessfulRequests(
                        targets,
                        Math.max(1, strategySettings.proxyTestRequests),
                        Math.max(1, strategySettings.proxyTestTimeoutSeconds),
                        Math.max(1, strategySettings.proxyTestConcurrencyLimit),
                        runner.getDialHost(),
                        runner.getDialPort(),
                        strategySettings.proxyAuthEnabled ? strategySettings.resolveRuntimeProxyUsername() : "",
                        strategySettings.proxyAuthEnabled ? strategySettings.resolveRuntimeProxyPassword() : ""
                    );
                    result.successCount = successCount;
                } catch (Exception error) {
                    result.successCount = 0;
                }

                result.completed = true;
                int currentIndex = index + 1;
                runOnUiThread(() -> {
                    results.sort(Comparator.comparingInt((ByeDpiStrategyResult item) -> item.successCount).reversed());
                    adapter.replaceItems(results);
                    binding.textStatus.setText(
                        getString(R.string.byedpi_proxytest_progress, currentIndex, strategies.size())
                    );
                });
            }
            runOnUiThread(() -> {
                testing = false;
                adapter.setTesting(false);
                results.sort(Comparator.comparingInt((ByeDpiStrategyResult item) -> item.successCount).reversed());
                adapter.replaceItems(results);
                binding.progressIndicator.setVisibility(View.GONE);
                binding.buttonStartStopTest.setText(R.string.byedpi_proxytest_start);
                bindCompletionSummary(results);
            });
        });
    }

    private void stopTesting() {
        testing = false;
        Future<?> currentTask = testTask;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        testTask = null;
        runOnUiThread(() -> {
            adapter.setTesting(false);
            binding.progressIndicator.setVisibility(View.GONE);
            binding.buttonStartStopTest.setText(R.string.byedpi_proxytest_start);
            if (TextUtils.isEmpty(binding.textStatus.getText())) {
                renderIdleState();
            }
        });
    }

    private void renderIdleState() {
        binding.textStatus.setText(R.string.byedpi_proxytest_idle);
        binding.buttonStartStopTest.setText(R.string.byedpi_proxytest_start);
        binding.progressIndicator.setVisibility(View.GONE);
    }

    private void bindCompletionSummary(List<ByeDpiStrategyResult> results) {
        ByeDpiStrategyResult best = null;
        for (ByeDpiStrategyResult result : results) {
            if (!result.completed) {
                continue;
            }
            if (best == null || result.successCount > best.successCount) {
                best = result;
            }
        }
        if (best == null) {
            binding.textStatus.setText(R.string.byedpi_proxytest_complete);
            return;
        }
        binding.textStatus.setText(
            getString(R.string.byedpi_proxytest_complete_best, best.successCount, best.totalRequests)
        );
    }
}
