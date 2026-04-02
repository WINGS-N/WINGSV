package wings.v.root;

import android.content.Context;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Arrays;

public final class RootCommandMain {
    private RootCommandMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("Missing root command");
        }
        Context context = obtainSystemContext();
        String command = args[0];
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
        if ("tether".equals(command)) {
            RootTetheringCommands.handle(context, commandArgs);
            return;
        }
        if ("shell".equals(command)) {
            RootShellCommands.handle(commandArgs);
            return;
        }
        throw new IllegalArgumentException("Unknown root command: " + command);
    }

    private static Context obtainSystemContext() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain");
        Object activityThread = systemMainMethod.invoke(null);
        Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContextMethod.invoke(activityThread);
    }
}
