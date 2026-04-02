package wings.v.vpnhotspot.bridge.sharing;

public final class VpnHotspotShell {
    private VpnHotspotShell() {
    }

    public static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
