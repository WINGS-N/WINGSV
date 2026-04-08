package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityWarningConfirmBinding;

public class WarningConfirmActivity extends AppCompatActivity {

    private static final String EXTRA_WARNING_TEXT = "wings.v.extra.WARNING_TEXT";
    private static final String EXTRA_CONFIRM_DELAY_SECONDS = "wings.v.extra.CONFIRM_DELAY_SECONDS";
    private static final String STATE_DEADLINE_ELAPSED_MS = "state_deadline_elapsed_ms";
    private static final long COUNTDOWN_TICK_MS = 250L;

    private ActivityWarningConfirmBinding binding;
    private long confirmDeadlineElapsedMs;
    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            renderCountdown();
            if (SystemClock.elapsedRealtime() < confirmDeadlineElapsedMs) {
                binding.getRoot().postDelayed(this, COUNTDOWN_TICK_MS);
            }
        }
    };

    public static Intent createIntent(Context context, String warningText, int confirmDelaySeconds) {
        return new Intent(context, WarningConfirmActivity.class)
            .putExtra(EXTRA_WARNING_TEXT, warningText)
            .putExtra(EXTRA_CONFIRM_DELAY_SECONDS, Math.max(confirmDelaySeconds, 0));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWarningConfirmBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);

        String warningText = getIntent().getStringExtra(EXTRA_WARNING_TEXT);
        int confirmDelaySeconds = Math.max(getIntent().getIntExtra(EXTRA_CONFIRM_DELAY_SECONDS, 0), 0);
        binding.textWarningMessage.setText(warningText == null ? "" : warningText.trim());

        if (savedInstanceState != null) {
            confirmDeadlineElapsedMs = savedInstanceState.getLong(STATE_DEADLINE_ELAPSED_MS, 0L);
        }
        if (confirmDeadlineElapsedMs <= 0L) {
            confirmDeadlineElapsedMs = SystemClock.elapsedRealtime() + confirmDelaySeconds * 1_000L;
        }

        binding.buttonCancel.setOnClickListener(v -> {
            Haptics.softSelection(v);
            finishCancelled();
        });
        binding.buttonContinue.setOnClickListener(v -> {
            if (SystemClock.elapsedRealtime() < confirmDeadlineElapsedMs) {
                return;
            }
            Haptics.softConfirm(v);
            setResult(RESULT_OK);
            finish();
        });

        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    finishCancelled();
                }
            }
        );

        renderCountdown();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finishCancelled();
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding != null) {
            binding.getRoot().removeCallbacks(countdownRunnable);
            binding.getRoot().post(countdownRunnable);
        }
    }

    @Override
    protected void onStop() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(countdownRunnable);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.getRoot().removeCallbacks(countdownRunnable);
        }
        binding = null;
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_DEADLINE_ELAPSED_MS, confirmDeadlineElapsedMs);
    }

    private void renderCountdown() {
        if (binding == null) {
            return;
        }
        long remainingMs = Math.max(0L, confirmDeadlineElapsedMs - SystemClock.elapsedRealtime());
        boolean ready = remainingMs <= 0L;
        binding.buttonContinue.setEnabled(ready);
        int textColor = ContextCompat.getColor(
            this,
            ready ? R.color.wingsv_power_on_text : R.color.wingsv_text_secondary
        );
        binding.buttonContinue.setBackgroundResource(
            ready ? R.drawable.bg_warning_confirm_continue_enabled : R.drawable.bg_warning_confirm_continue_disabled
        );
        binding.buttonContinue.setTextColor(textColor);
        if (ready) {
            binding.textWarningTimer.setVisibility(View.INVISIBLE);
            binding.textWarningTimer.setText("");
        } else {
            binding.textWarningTimer.setVisibility(View.VISIBLE);
            binding.textWarningTimer.setText(
                getString(R.string.warning_confirm_timer_seconds, (int) Math.ceil(remainingMs / 1000d))
            );
        }
    }

    private void finishCancelled() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
