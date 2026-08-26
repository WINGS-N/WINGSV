package wings.v.core;

import androidx.annotation.NonNull;
import java.util.Locale;

/**
 * Maps a settings key to the MMKV file that owns it.
 *
 * <p>The settings used to live in one file holding every key in the app. Both the
 * main and the :tunnel process map that file and write to it, so every write
 * touched the same mapping regardless of which subsystem it belonged to, and any
 * corruption took the whole settings set with it. Splitting by subsystem keeps a
 * write in the file its own feature owns, and leaves the blast radius of a damaged
 * file to that feature.
 *
 * <p>This mapping is a storage contract: once a key is written into an area, that
 * is where it is read from. Changing which area a prefix maps to strands the data
 * already stored under it, so add prefixes rather than moving them.
 */
public final class MmkvPrefsAreas {

    public static final String AREA_XRAY = "wingsv_prefs_xray";
    public static final String AREA_VK = "wingsv_prefs_vk";
    public static final String AREA_SHARING = "wingsv_prefs_sharing";
    public static final String AREA_GUARDIAN = "wingsv_prefs_guardian";
    public static final String AREA_TUNNEL = "wingsv_prefs_tunnel";
    public static final String AREA_ROOT = "wingsv_prefs_root";
    public static final String AREA_WBSTREAM = "wingsv_prefs_wbstream";
    public static final String AREA_SUBSCRIPTION = "wingsv_prefs_subscription";
    /** Everything without a subsystem of its own: theme, onboarding, updates, misc. */
    public static final String AREA_APP = "wingsv_prefs_app";

    private static final String[] ALL_AREAS = {
        AREA_XRAY,
        AREA_VK,
        AREA_SHARING,
        AREA_GUARDIAN,
        AREA_TUNNEL,
        AREA_ROOT,
        AREA_WBSTREAM,
        AREA_SUBSCRIPTION,
        AREA_APP,
    };

    private static final String KEY_PREFIX = "pref_";

    private MmkvPrefsAreas() {}

    public static String[] allAreas() {
        return ALL_AREAS.clone();
    }

    /** The area owning key. Unknown keys land in AREA_APP, never nowhere. */
    public static String areaFor(@NonNull String key) {
        String normalized = key.toLowerCase(Locale.US);
        if (normalized.startsWith(KEY_PREFIX)) {
            normalized = normalized.substring(KEY_PREFIX.length());
        }
        String head = normalized;
        int separator = normalized.indexOf('_');
        if (separator > 0) {
            head = normalized.substring(0, separator);
        }
        switch (head) {
            case "xray":
                return AREA_XRAY;
            case "vk":
            case "captcha":
                return AREA_VK;
            case "sharing":
            case "tether":
            case "hotspot":
                return AREA_SHARING;
            case "guardian":
                return AREA_GUARDIAN;
            case "wg":
            case "awg":
            case "turn":
            case "backend":
            case "amnezia":
                return AREA_TUNNEL;
            case "root":
            case "rootd":
            case "xposed":
                return AREA_ROOT;
            case "wb":
                return AREA_WBSTREAM;
            case "subscription":
            case "subscriptions":
                return AREA_SUBSCRIPTION;
            default:
                return AREA_APP;
        }
    }
}
