package wings.v.core;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

public final class Haptics {
    private Haptics() {
    }

    public static void softSelection(@Nullable View view) {
        if (view == null) {
            return;
        }
        if (tryAdvancedEffect(view.getContext(), false)) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void softSliderStep(@Nullable View view) {
        if (view == null) {
            return;
        }
        if (trySliderEffect(view.getContext())) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK);
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    public static void softConfirm(@Nullable View view) {
        if (view == null) {
            return;
        }
        if (tryAdvancedEffect(view.getContext(), true)) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    public static void powerWave(@Nullable View view, boolean turningOn) {
        if (view == null) {
            return;
        }
        if (tryPowerWaveEffect(view.getContext(), turningOn)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                    turningOn ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF
            );
            return;
        }
        softConfirm(view);
    }

    private static boolean tryAdvancedEffect(Context context, boolean confirm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
            )) {
                VibrationEffect.Composition composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f);

                if (confirm && vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK
                )) {
                    composition
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f, 25)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.2f, 20);
                } else {
                    composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.2f, 18);
                }

                vibrator.vibrate(composition.compose());
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean trySliderEffect(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (!vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
            )) {
                return false;
            }

            VibrationEffect effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.26f, 14)
                    .compose();
            vibrator.vibrate(effect);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean tryPowerWaveEffect(Context context, boolean turningOn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            Vibrator vibrator = getVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return false;
            }

            if (!vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK
            )) {
                return false;
            }

            VibrationEffect.Composition composition = VibrationEffect.startComposition();
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
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Nullable
    private static Vibrator getVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
        return context.getSystemService(Vibrator.class);
    }
}
