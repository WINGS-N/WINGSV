package wings.v.byedpi;

public final class ByeDpiNative {
    static {
        System.loadLibrary("byedpi");
    }

    public int startProxy(String[] args) {
        return jniStartProxy(args);
    }

    public int stopProxy() {
        return jniStopProxy();
    }

    public int forceClose() {
        return jniForceClose();
    }

    private native int jniStartProxy(String[] args);
    private native int jniStopProxy();
    private native int jniForceClose();
}
