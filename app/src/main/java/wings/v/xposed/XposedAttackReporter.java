package wings.v.xposed;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedHelpers;
import wings.v.receiver.XposedStatsReceiver;

public final class XposedAttackReporter {

    private static final String MODULE_PACKAGE = "wings.v";

    private XposedAttackReporter() {}

    public static void reportAppEvent(@NonNull String vector, @Nullable String detail) {
        Context context = resolveContext();
        if (context == null) {
            return;
        }
        dispatch(context, null, vector, "java", extractCallerMethod(context.getPackageName()), detail);
    }

    public static void reportSystemEvent(
        @Nullable String packageName,
        @NonNull String vector,
        @Nullable String detail
    ) {
        Context context = resolveContext();
        if (context == null) {
            return;
        }
        dispatch(context, packageName, vector, "java", null, detail);
    }

    public static void reportNativeEvent(@NonNull String vector, @Nullable String detail) {
        Context context = resolveContext();
        if (context == null) {
            return;
        }
        dispatch(context, null, vector, "native", null, detail);
    }

    private static void dispatch(
        @NonNull Context context,
        @Nullable String explicitPackageName,
        @NonNull String vector,
        @NonNull String source,
        @Nullable String callerMethod,
        @Nullable String detail
    ) {
        String packageName = normalize(
            !TextUtils.isEmpty(explicitPackageName) ? explicitPackageName : context.getPackageName()
        );
        if (TextUtils.isEmpty(packageName) || TextUtils.equals(MODULE_PACKAGE, packageName)) {
            return;
        }
        String normalizedVector = normalize(vector);
        String normalizedDetail = normalize(detail);
        Intent intent = new Intent(XposedStatsReceiver.ACTION_RECORD_EVENT)
            .setClassName(MODULE_PACKAGE, MODULE_PACKAGE + ".receiver.XposedStatsReceiver")
            .setPackage(MODULE_PACKAGE)
            .putExtra(XposedStatsReceiver.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(XposedStatsReceiver.EXTRA_VECTOR, normalizedVector)
            .putExtra(XposedStatsReceiver.EXTRA_SOURCE, source)
            .putExtra(XposedStatsReceiver.EXTRA_CALLER_METHOD, normalize(callerMethod))
            .putExtra(XposedStatsReceiver.EXTRA_DETAIL, normalizedDetail)
            .putExtra(XposedStatsReceiver.EXTRA_TIMESTAMP, System.currentTimeMillis());
        try {
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    @Nullable
    private static Context resolveContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Application application = (Application) XposedHelpers.callStaticMethod(
                activityThreadClass,
                "currentApplication"
            );
            if (application != null) {
                return application.getApplicationContext();
            }
            Object currentThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object systemContext =
                currentThread != null ? XposedHelpers.callMethod(currentThread, "getSystemContext") : null;
            return systemContext instanceof Context ? (Context) systemContext : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Nullable
    private static String extractCallerMethod(@Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement frame : stackTrace) {
                if (frame == null) {
                    continue;
                }
                String className = frame.getClassName();
                if (
                    TextUtils.isEmpty(className) ||
                    !className.startsWith(packageName) ||
                    className.startsWith("wings.v.xposed.") ||
                    className.startsWith("de.robv.android.xposed.")
                ) {
                    continue;
                }
                String methodName = frame.getMethodName();
                if (TextUtils.isEmpty(methodName) || "getStackTrace".equals(methodName)) {
                    continue;
                }
                return className + "#" + methodName;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
