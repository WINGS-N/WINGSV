package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityWarningConfirmBinding;

@SuppressWarnings("PMD.CommentRequired")
/** Shows a timed warning before applying a risky setting change. */
public class WarningConfirmActivity extends AppCompatActivity {

    private static final String EXTRA_WARN = "wings.v.extra.WARNING_TEXT";
    private static final String EXTRA_DELAY_S = "wings.v.extra.CONFIRM_DELAY_SECONDS";
    private static final String STATE_DEADLINE_MS = "state_deadline_elapsed_ms";
    private static final long COUNTDOWN_TICK_MS = 250L;
    private static final long NO_DEADLINE_MS = 0L;
    private static final int READY_TEXT_COLOR = R.color.wingsv_power_on_text;
    private static final int IDLE_TEXT_COLOR = R.color.wingsv_text_secondary;
    private static final int READY_BG = R.drawable.bg_warning_confirm_continue_enabled;
    private static final int IDLE_BG = R.drawable.bg_warning_confirm_continue_disabled;

    private View rootView;
    private ToolbarLayout toolbarLayout;
    private TextView warningMessageView;
    private TextView timerView;
    private Button cancelButton;
    private Button continueButton;
    private long confirmDeadlineMs;
    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (rootView == null) {
                return;
            }
            renderCountdown();
            if (SystemClock.elapsedRealtime() < confirmDeadlineMs) {
                rootView.postDelayed(this, COUNTDOWN_TICK_MS);
            }
        }
    };

    /** Required empty constructor. */
    public WarningConfirmActivity() {
        super();
    }

    /** Creates an intent for the warning confirmation screen. */
    public static Intent createIntent(final Context context, final String warningText, final int confirmDelaySec) {
        return new Intent(context, WarningConfirmActivity.class)
            .putExtra(EXTRA_WARN, warningText)
            .putExtra(EXTRA_DELAY_S, Math.max(confirmDelaySec, 0));
    }

    @Override
    protected void onCreate(@Nullable final Bundle state) {
        super.onCreate(state);
        final ActivityWarningConfirmBinding binding = ActivityWarningConfirmBinding.inflate(getLayoutInflater());
        rootView = binding.getRoot();
        setContentView(rootView);

        toolbarLayout = findViewById(R.id.toolbar_layout);
        warningMessageView = findViewById(R.id.text_warning_message);
        timerView = findViewById(R.id.text_warning_timer);
        cancelButton = findViewById(R.id.button_cancel);
        continueButton = findViewById(R.id.button_continue);

        toolbarLayout.setShowNavigationButtonAsBack(true);

        final String warningText = getIntent().getStringExtra(EXTRA_WARN);
        final int confirmDelaySec = Math.max(getIntent().getIntExtra(EXTRA_DELAY_S, 0), 0);
        warningMessageView.setText(warningText == null ? "" : warningText.trim());

        if (state != null) {
            confirmDeadlineMs = state.getLong(STATE_DEADLINE_MS, NO_DEADLINE_MS);
        }
        if (confirmDeadlineMs <= NO_DEADLINE_MS) {
            confirmDeadlineMs = SystemClock.elapsedRealtime() + confirmDelaySec * 1_000L;
        }

        cancelButton.setOnClickListener(v -> {
            Haptics.softSelection(v);
            finishCancelled();
        });
        continueButton.setOnClickListener(v -> {
            if (SystemClock.elapsedRealtime() < confirmDeadlineMs) {
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
        if (rootView != null) {
            rootView.removeCallbacks(countdownRunnable);
            rootView.post(countdownRunnable);
        }
    }

    @Override
    protected void onStop() {
        if (rootView != null) {
            rootView.removeCallbacks(countdownRunnable);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (rootView != null) {
            rootView.removeCallbacks(countdownRunnable);
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_DEADLINE_MS, confirmDeadlineMs);
    }

    private void renderCountdown() {
        if (continueButton == null || timerView == null) {
            return;
        }
        final long remainingMs = Math.max(0L, confirmDeadlineMs - SystemClock.elapsedRealtime());
        final boolean ready = remainingMs <= NO_DEADLINE_MS;
        continueButton.setEnabled(ready);
        final int textColor = ContextCompat.getColor(this, ready ? READY_TEXT_COLOR : IDLE_TEXT_COLOR);
        continueButton.setBackgroundResource(ready ? READY_BG : IDLE_BG);
        continueButton.setTextColor(textColor);
        if (ready) {
            timerView.setVisibility(View.INVISIBLE);
            timerView.setText("");
        } else {
            timerView.setVisibility(View.VISIBLE);
            timerView.setText(getString(R.string.warning_confirm_timer_seconds, (int) Math.ceil(remainingMs / 1000d)));
        }
    }

    private void finishCancelled() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
