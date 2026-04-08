package wings.v.core;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import androidx.annotation.Nullable;

@SuppressWarnings(
    { "PMD.AvoidCatchingGenericException", "PMD.CommentRequired", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" }
)
public final class Haptics {

    private Haptics() {}

    public static void softSelection(@Nullable final View view) {
        if (view == null) {
            return;
        }
        final boolean handled = tryAdvancedEffect(view.getContext(), false);
        if (!handled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    public static void softSliderStep(@Nullable final View view) {
        if (view == null) {
            return;
        }
        final boolean handled = trySliderEffect(view.getContext());
        if (!handled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    public static void softConfirm(@Nullable final View view) {
        if (view == null) {
            return;
        }
        final boolean handled = tryAdvancedEffect(view.getContext(), true);
        if (!handled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
    }

    public static void powerWave(@Nullable final View view, final boolean turningOn) {
        if (view == null) {
            return;
        }
        final boolean handled = tryPowerWaveEffect(view.getContext(), turningOn);
        if (!handled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                view.performHapticFeedback(
                    turningOn ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF
                );
            } else {
                softConfirm(view);
            }
        }
    }

    private static boolean tryAdvancedEffect(final Context context, final boolean confirm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            final Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
                )
            ) {
                final VibrationEffect.Composition composition = VibrationEffect.startComposition().addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    0.35f
                );

                if (confirm && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    composition
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f, 25)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.2f, 20);
                } else {
                    composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.2f, 18);
                }

                vibrator.vibrate(composition.compose());
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean trySliderEffect(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            final Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (
                !vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
                )
            ) {
                return false;
            }

            final VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.26f, 14)
                .compose();
            vibrator.vibrate(effect);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean tryPowerWaveEffect(final Context context, final boolean turningOn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            final Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (
                !vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                )
            ) {
                return false;
            }

            final VibrationEffect.Composition composition = VibrationEffect.startComposition();
            if (turningOn) {
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.18f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.28f, 16)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 18)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.58f, 22)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.16f, 20);
            } else {
                composition
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.38f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 18)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.2f, 22);
            }

            vibrator.vibrate(composition.compose());
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Nullable
    private static Vibrator getVibrator(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            final VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return context.getSystemService(Vibrator.class);
    }
}
