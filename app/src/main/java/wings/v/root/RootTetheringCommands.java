package wings.v.root;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.TetheringManager;
import android.os.Build;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import wings.v.core.TetherType;

@SuppressLint("NewApi")
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.AvoidAccessibilityAlteration",
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.SignatureDeclareThrowsException",
        "PMD.LawOfDemeter",
        "PMD.AvoidLiteralsInIfCondition",
        "PMD.CommentDefaultAccessModifier",
        "PMD.OnlyOneReturn",
    }
)
final class RootTetheringCommands {

    private static final long TETHER_CALLBACK_TIMEOUT_SECONDS = 15L;

    private RootTetheringCommands() {}

    static void handle(Context context, String... args) throws Exception {
        if (args == null || args.length < 2) {
            throw new IllegalArgumentException("Usage: tether <start|stop> <wifi|usb|bluetooth|ethernet>");
        }
        String action = args[0];
        TetherType tetherType = TetherType.fromCommandName(args[1]);
        if ("start".equals(action)) {
            start(context, tetherType);
            return;
        }
        if ("stop".equals(action)) {
            stop(context, tetherType);
            return;
        }
        throw new IllegalArgumentException("Unknown tether action: " + action);
    }

    private static void start(Context context, TetherType tetherType) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("Root tethering controls require Android 11+");
        }
        TetheringManager tetheringManager = context.getSystemService(TetheringManager.class);
        if (tetheringManager == null) {
            throw new IllegalStateException("TetheringManager unavailable");
        }

        Executor directExecutor = Runnable::run;
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<String> failureMessage = new AtomicReference<>(null);
        tetheringManager.startTethering(
            buildRequest(tetherType),
            directExecutor,
            new TetheringManager.StartTetheringCallback() {
                @Override
                public void onTetheringStarted() {
                    completionLatch.countDown();
                }

                @Override
                public void onTetheringFailed(int error) {
                    failureMessage.set("StartTethering failed: " + error);
                    completionLatch.countDown();
                }
            }
        );
        awaitTetherCallback(completionLatch, failureMessage, "start");
    }

    private static void stop(Context context, TetherType tetherType) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new IllegalStateException("Root tethering controls require Android 11+");
        }
        TetheringManager tetheringManager = context.getSystemService(TetheringManager.class);
        if (tetheringManager == null) {
            throw new IllegalStateException("TetheringManager unavailable");
        }
        Executor directExecutor = Runnable::run;
        tetheringManager.stopTethering(
            buildRequest(tetherType),
            directExecutor,
            new TetheringManager.StopTetheringCallback() {}
        );
        Thread.sleep(1_000L);
    }

    private static TetheringManager.TetheringRequest buildRequest(TetherType tetherType) throws Exception {
        TetheringManager.TetheringRequest.Builder builder = new TetheringManager.TetheringRequest.Builder(
            tetherType.systemType
        );
        invokeBooleanBuilderMethod(builder, "setShouldShowEntitlementUi", false);
        invokeBooleanBuilderMethod(builder, "setExemptFromEntitlementCheck", true);
        return builder.build();
    }

    private static void invokeBooleanBuilderMethod(
        TetheringManager.TetheringRequest.Builder builder,
        String methodName,
        boolean value
    ) {
        try {
            Method method = TetheringManager.TetheringRequest
                .Builder.class.getDeclaredMethod(methodName, boolean.class);
            method.setAccessible(true);
            method.invoke(builder, value);
        } catch (Exception ignored) {}
    }

    private static void awaitTetherCallback(
        CountDownLatch completionLatch,
        AtomicReference<String> failureMessage,
        String action
    ) throws Exception {
        if (!completionLatch.await(TETHER_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "System did not confirm tether " + action + " within " + TETHER_CALLBACK_TIMEOUT_SECONDS + "s"
            );
        }
        String failure = failureMessage.get();
        if (failure != null && !failure.isEmpty()) {
            throw new IllegalStateException(failure);
        }
    }
}
