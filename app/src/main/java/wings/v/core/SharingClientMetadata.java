package wings.v.core;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SharingClientMetadata {

    public final byte[] mac;

    @Nullable
    public final String ipAddress;

    @Nullable
    public final String interfaceName;

    @Nullable
    public final String vendor;

    public final long firstSeenMillis;

    public SharingClientMetadata(
        @NonNull byte[] mac,
        @Nullable String ipAddress,
        @Nullable String interfaceName,
        @Nullable String vendor,
        long firstSeenMillis
    ) {
        this.mac = mac;
        this.ipAddress = ipAddress;
        this.interfaceName = interfaceName;
        this.vendor = vendor;
        this.firstSeenMillis = firstSeenMillis;
    }

    public static final class ArpEntry {

        public final String ipAddress;
        public final String interfaceName;

        public ArpEntry(String ipAddress, String interfaceName) {
            this.ipAddress = ipAddress;
            this.interfaceName = interfaceName;
        }
    }

    @NonNull
    public static Map<String, ArpEntry> readArpTable() {
        Map<String, ArpEntry> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            reader.readLine();
            while (true) {
                String line = reader.readLine();
                if (line == null) break;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 6) continue;
                String ip = tokens[0];
                String mac = tokens[3];
                String iface = tokens[5];
                if (mac.length() == 0 || "00:00:00:00:00:00".equals(mac)) continue;
                result.put(mac.toLowerCase(Locale.ROOT), new ArpEntry(ip, iface));
            }
        } catch (IOException ignored) {}
        return result;
    }

    @NonNull
    public static String macKey(@NonNull byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int index = 0; index < mac.length; index++) {
            if (index > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02x", mac[index]));
        }
        return sb.toString();
    }

    @Nullable
    public static String lookupVendor(@NonNull byte[] mac, @NonNull Context context) {
        if (mac.length < 3) return null;
        if ((mac[0] & 0x02) != 0) return null;
        String prefix = String.format(Locale.ROOT, "%02X:%02X:%02X", mac[0] & 0xFF, mac[1] & 0xFF, mac[2] & 0xFF);
        return OuiTable.lookup(prefix);
    }
}
