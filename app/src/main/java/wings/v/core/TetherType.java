package wings.v.core;

import android.content.Intent;
import android.net.TetheringManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn" })
public enum TetherType {
    WIFI("wifi", TetheringManager.TETHERING_WIFI),
    USB("usb", 1),
    BLUETOOTH("bluetooth", 2),
    ETHERNET("ethernet", 5);

    public static final String ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED";
    private static final String EXTRA_ACTIVE_TETHER = "tetherArray";

    public final String commandName;
    public final int systemType;

    TetherType(final String commandName, final int systemType) {
        this.commandName = commandName;
        this.systemType = systemType;
    }

    public static TetherType fromCommandName(final String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Unknown tether type");
        }
        for (final TetherType value : values()) {
            if (value.commandName.equalsIgnoreCase(rawValue)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown tether type: " + rawValue);
    }

    public static Set<TetherType> readEnabledTypes(final Intent intent) {
        final Set<TetherType> types = EnumSet.noneOf(TetherType.class);
        for (final String iface : readEnabledInterfaces(intent)) {
            final TetherType type = detectFromInterface(iface);
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

    public static Set<String> readEnabledInterfaces(final Intent intent) {
        final Set<String> interfaces = new HashSet<>();
        if (intent == null) {
            return interfaces;
        }
        final ArrayList<String> tetheredList = intent.getStringArrayListExtra(EXTRA_ACTIVE_TETHER);
        if (tetheredList != null) {
            for (final String iface : tetheredList) {
                if (iface != null && !iface.isBlank()) {
                    interfaces.add(iface.trim());
                }
            }
        }
        if (!interfaces.isEmpty()) {
            return interfaces;
        }
        final String[] tetheredArray = intent.getStringArrayExtra(EXTRA_ACTIVE_TETHER);
        if (tetheredArray == null) {
            return interfaces;
        }
        for (final String iface : tetheredArray) {
            if (iface != null && !iface.isBlank()) {
                interfaces.add(iface.trim());
            }
        }
        return interfaces;
    }

    public static boolean isEthernetSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static TetherType detectFromInterfaceName(final String iface) {
        return detectFromInterface(iface);
    }

    private static TetherType detectFromInterface(final String iface) {
        if (iface == null) {
            return null;
        }
        final String normalized = iface.toLowerCase(Locale.US);
        if (
            normalized.startsWith("wlan") ||
            normalized.startsWith("swlan") ||
            normalized.startsWith("softap") ||
            normalized.startsWith("ap")
        ) {
            return WIFI;
        }
        if (normalized.startsWith("rndis") || normalized.startsWith("usb") || normalized.startsWith("ncm")) {
            return USB;
        }
        if (normalized.startsWith("bnep") || normalized.startsWith("bt-pan") || normalized.startsWith("bt")) {
            return BLUETOOTH;
        }
        if (normalized.startsWith("eth") || normalized.startsWith("en") || normalized.contains("ether")) {
            return ETHERNET;
        }
        return null;
    }
}
