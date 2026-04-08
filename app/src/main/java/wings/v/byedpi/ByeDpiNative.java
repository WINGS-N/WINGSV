package wings.v.byedpi;

@SuppressWarnings("PMD.AvoidUsingNativeCode")
public final class ByeDpiNative {

    static {
        System.loadLibrary("byedpi");
    }

    public int startProxy(String... args) {
        return jniStartProxy(args);
    }

    public void stopProxy() {
        jniStopProxy();
    }

    public void forceClose() {
        jniForceClose();
    }

    private native int jniStartProxy(String... args);

    private native int jniStopProxy();

    private native int jniForceClose();
}
