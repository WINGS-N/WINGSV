package wings.v.core;

import android.content.Intent;
import android.net.TetheringManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public enum TetherType {
    WIFI("wifi", TetheringManager.TETHERING_WIFI),
    USB("usb", 1),
    BLUETOOTH("bluetooth", 2),
    ETHERNET("ethernet", 5);

    public static final String ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED";
    private static final String EXTRA_ACTIVE_TETHER = "tetherArray";

    public final String commandName;
    public final int systemType;

    TetherType(String commandName, int systemType) {
        this.commandName = commandName;
        this.systemType = systemType;
    }

    public static TetherType fromCommandName(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Unknown tether type");
        }
        for (TetherType value : values()) {
            if (value.commandName.equalsIgnoreCase(rawValue)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown tether type: " + rawValue);
    }

    public static Set<TetherType> readEnabledTypes(Intent intent) {
        Set<TetherType> types = new HashSet<>();
        for (String iface : readEnabledInterfaces(intent)) {
            TetherType type = detectFromInterface(iface);
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

    public static Set<String> readEnabledInterfaces(Intent intent) {
        Set<String> interfaces = new HashSet<>();
        if (intent == null) {
            return interfaces;
        }
        ArrayList<String> tetheredList = intent.getStringArrayListExtra(EXTRA_ACTIVE_TETHER);
        if (tetheredList != null) {
            for (String iface : tetheredList) {
                if (iface != null && !iface.trim().isEmpty()) {
                    interfaces.add(iface.trim());
                }
            }
        }
        if (!interfaces.isEmpty()) {
            return interfaces;
        }
        String[] tetheredArray = intent.getStringArrayExtra(EXTRA_ACTIVE_TETHER);
        if (tetheredArray == null) {
            return interfaces;
        }
        for (String iface : tetheredArray) {
            if (iface != null && !iface.trim().isEmpty()) {
                interfaces.add(iface.trim());
            }
        }
        return interfaces;
    }

    public static boolean isEthernetSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static TetherType detectFromInterfaceName(String iface) {
        return detectFromInterface(iface);
    }

    private static TetherType detectFromInterface(String iface) {
        if (iface == null) {
            return null;
        }
        String normalized = iface.toLowerCase(Locale.US);
        if (normalized.startsWith("wlan")
                || normalized.startsWith("swlan")
                || normalized.startsWith("softap")
                || normalized.startsWith("ap")) {
            return WIFI;
        }
        if (normalized.startsWith("rndis")
                || normalized.startsWith("usb")
                || normalized.startsWith("ncm")) {
            return USB;
        }
        if (normalized.startsWith("bnep")
                || normalized.startsWith("bt-pan")
                || normalized.startsWith("bt")) {
            return BLUETOOTH;
        }
        if (normalized.startsWith("eth")
                || normalized.startsWith("en")
                || normalized.contains("ether")) {
            return ETHERNET;
        }
        return null;
    }
}
