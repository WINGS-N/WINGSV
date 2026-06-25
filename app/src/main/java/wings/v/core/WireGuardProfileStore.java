package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

/**
 * Per-profile store for the WireGuard backend, mirroring XrayStore's profile
 * API. Phase 1 of multi-profile support: this persists the profile list, the
 * active id, favorites and traffic stats, plus a one-time migration that seeds
 * a single profile from the legacy flat KEY_WG_* keys. The flat keys remain the
 * source of truth for the running tunnel until later phases wire the UI; the
 * applyActiveToPrefs shim writes the active profile back into those flat keys.
 */
@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class WireGuardProfileStore {

    private static final String DEFAULT_DNS = "1.1.1.1, 1.0.0.1";
    private static final String DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0";
    private static final int DEFAULT_MTU = 1280;

    private WireGuardProfileStore() {}

    public static List<WireGuardProfile> getProfiles(Context context) {
        ArrayList<WireGuardProfile> result = new ArrayList<>();
        JSONArray array = ProfileStoreSupport.readArray(context, AppPrefs.KEY_WG_PROFILES_JSON);
        for (int index = 0; index < array.length(); index++) {
            WireGuardProfile profile = WireGuardProfile.fromJson(array.optJSONObject(index));
            if (profile != null && !profile.isEmpty()) {
                result.add(profile);
            }
        }
        return result;
    }

    public static void setProfiles(Context context, List<WireGuardProfile> profiles) {
        JSONArray array = new JSONArray();
        Map<String, WireGuardProfile> deduped = new LinkedHashMap<>();
        if (profiles != null) {
            for (WireGuardProfile profile : profiles) {
                if (profile == null || profile.isEmpty()) {
                    continue;
                }
                deduped.put(profile.id, profile);
            }
        }
        for (WireGuardProfile profile : deduped.values()) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {}
        }
        ProfileStoreSupport.prefs(context).edit().putString(AppPrefs.KEY_WG_PROFILES_JSON, array.toString()).commit();
        ProfileStoreSupport.pruneTrafficStats(context, AppPrefs.KEY_WG_PROFILE_TRAFFIC_JSON, deduped.keySet());
    }

    public static boolean replaceProfile(Context context, WireGuardProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id) || profile.isEmpty()) {
            return false;
        }
        ArrayList<WireGuardProfile> profiles = new ArrayList<>(getProfiles(context));
        for (int index = 0; index < profiles.size(); index++) {
            WireGuardProfile candidate = profiles.get(index);
            if (candidate != null && TextUtils.equals(candidate.id, profile.id)) {
                profiles.set(index, profile);
                setProfiles(context, profiles);
                return true;
            }
        }
        profiles.add(profile);
        setProfiles(context, profiles);
        return true;
    }

    public static WireGuardProfile getProfileById(Context context, String profileId) {
        if (TextUtils.isEmpty(ProfileStoreSupport.trim(profileId))) {
            return null;
        }
        for (WireGuardProfile profile : getProfiles(context)) {
            if (profile != null && TextUtils.equals(profile.id, profileId)) {
                return profile;
            }
        }
        return null;
    }

    public static boolean deleteProfile(Context context, String profileId) {
        String trimmedId = ProfileStoreSupport.trim(profileId);
        if (TextUtils.isEmpty(trimmedId)) {
            return false;
        }
        // A transport shared with a VkTurnProfile must not be deleted out from
        // under it; the VK TURN profile references it by id.
        if (VkTurnProfileStore.isTransportReferenced(context, VkTurnProfile.TRANSPORT_KIND_WG, trimmedId)) {
            return false;
        }
        ArrayList<WireGuardProfile> profiles = new ArrayList<>(getProfiles(context));
        boolean removed = false;
        for (int index = profiles.size() - 1; index >= 0; index--) {
            WireGuardProfile candidate = profiles.get(index);
            if (candidate != null && TextUtils.equals(candidate.id, trimmedId)) {
                profiles.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        setProfiles(context, profiles);
        ProfileStoreSupport.setFavorite(context, AppPrefs.KEY_WG_FAVORITE_PROFILE_IDS, trimmedId, false);
        if (TextUtils.equals(getActiveProfileId(context), trimmedId)) {
            setActiveProfileId(context, "");
            ensureActivePresent(context);
        }
        return true;
    }

    public static String getActiveProfileId(Context context) {
        return ProfileStoreSupport.getActiveProfileId(context, AppPrefs.KEY_WG_ACTIVE_PROFILE_ID);
    }

    public static void setActiveProfileId(Context context, String profileId) {
        ProfileStoreSupport.setActiveProfileId(context, AppPrefs.KEY_WG_ACTIVE_PROFILE_ID, profileId);
    }

    public static WireGuardProfile getActiveProfile(Context context) {
        WireGuardProfile profile = getProfileById(context, getActiveProfileId(context));
        if (profile != null) {
            return profile;
        }
        return ensureActivePresent(context);
    }

    public static WireGuardProfile ensureActivePresent(Context context) {
        WireGuardProfile active = getProfileById(context, getActiveProfileId(context));
        if (active != null) {
            return active;
        }
        List<WireGuardProfile> profiles = getProfiles(context);
        if (profiles.isEmpty()) {
            return null;
        }
        WireGuardProfile first = profiles.get(0);
        setActiveProfileId(context, first.id);
        return first;
    }

    public static Set<String> getFavoriteProfileIds(Context context) {
        return ProfileStoreSupport.getFavoriteProfileIds(context, AppPrefs.KEY_WG_FAVORITE_PROFILE_IDS);
    }

    public static boolean isFavorite(Context context, String profileId) {
        return ProfileStoreSupport.isFavorite(context, AppPrefs.KEY_WG_FAVORITE_PROFILE_IDS, profileId);
    }

    public static boolean toggleFavorite(Context context, String profileId) {
        return ProfileStoreSupport.toggleFavorite(context, AppPrefs.KEY_WG_FAVORITE_PROFILE_IDS, profileId);
    }

    public static Map<String, XrayStore.ProfileTrafficStats> getProfileTrafficStatsMap(Context context) {
        return ProfileStoreSupport.getTrafficStatsMap(context, AppPrefs.KEY_WG_PROFILE_TRAFFIC_JSON);
    }

    public static void addProfileTrafficDelta(Context context, String profileId, long rxDelta, long txDelta) {
        ProfileStoreSupport.addTrafficDelta(context, AppPrefs.KEY_WG_PROFILE_TRAFFIC_JSON, profileId, rxDelta, txDelta);
    }

    public static void resetProfileTrafficStats(Context context, Collection<String> profileIds) {
        ProfileStoreSupport.resetTrafficStats(context, AppPrefs.KEY_WG_PROFILE_TRAFFIC_JSON, profileIds);
    }

    /**
     * Writes the active profile's fields back into the legacy flat KEY_WG_*
     * keys so the existing single-config tunnel keeps reading them. No-op when
     * there is no active profile.
     */
    public static void applyActiveToPrefs(Context context) {
        WireGuardProfile profile = getActiveProfile(context);
        if (profile == null) {
            return;
        }
        applyProfileToPrefs(context, profile);
    }

    static void applyProfileToPrefs(Context context, WireGuardProfile profile) {
        if (profile == null) {
            return;
        }
        ProfileStoreSupport.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_WG_PRIVATE_KEY, ProfileStoreSupport.trim(profile.privateKey))
            .putString(AppPrefs.KEY_WG_ADDRESSES, ProfileStoreSupport.trim(profile.addresses))
            .putString(
                AppPrefs.KEY_WG_DNS,
                TextUtils.isEmpty(ProfileStoreSupport.trim(profile.dns))
                    ? DEFAULT_DNS
                    : ProfileStoreSupport.trim(profile.dns)
            )
            .putString(AppPrefs.KEY_WG_MTU, String.valueOf(profile.mtu > 0 ? profile.mtu : DEFAULT_MTU))
            .putString(AppPrefs.KEY_WG_PUBLIC_KEY, ProfileStoreSupport.trim(profile.publicKey))
            .putString(AppPrefs.KEY_WG_PRESHARED_KEY, ProfileStoreSupport.trim(profile.presharedKey))
            .putString(
                AppPrefs.KEY_WG_ALLOWED_IPS,
                TextUtils.isEmpty(ProfileStoreSupport.trim(profile.allowedIps))
                    ? DEFAULT_ALLOWED_IPS
                    : ProfileStoreSupport.trim(profile.allowedIps)
            )
            .commit();
    }

    static WireGuardProfile readFlatProfile(Context context, String title) {
        android.content.SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        return new WireGuardProfile(
            null,
            title,
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_PRIVATE_KEY, "")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_ADDRESSES, "")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_DNS, DEFAULT_DNS)),
            parseInt(prefs.getString(AppPrefs.KEY_WG_MTU, String.valueOf(DEFAULT_MTU)), DEFAULT_MTU),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_PUBLIC_KEY, "")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_PRESHARED_KEY, "")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_ALLOWED_IPS, DEFAULT_ALLOWED_IPS)),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_WG_ENDPOINT, ""))
        );
    }

    /**
     * One-time migration: seeds a single profile from the legacy flat KEY_WG_*
     * keys when they hold a non-empty config. Idempotent and gated by the
     * KEY_WG_PROFILES_MIGRATED flag. Returns the seeded (or pre-existing active)
     * profile, or null when there was nothing to migrate.
     */
    public static WireGuardProfile migrateFromFlatPrefs(Context context) {
        android.content.SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        if (prefs.getBoolean(AppPrefs.KEY_WG_PROFILES_MIGRATED, false)) {
            return getActiveProfile(context);
        }
        WireGuardProfile flat = readFlatProfile(context, "WireGuard");
        if (flat.isEmpty()) {
            prefs.edit().putBoolean(AppPrefs.KEY_WG_PROFILES_MIGRATED, true).commit();
            return null;
        }
        ArrayList<WireGuardProfile> profiles = new ArrayList<>(getProfiles(context));
        WireGuardProfile existing = findByDedupKey(profiles, flat.stableDedupKey());
        WireGuardProfile seeded = existing != null ? existing : flat;
        if (existing == null) {
            profiles.add(flat);
            setProfiles(context, profiles);
        }
        setActiveProfileId(context, seeded.id);
        prefs.edit().putBoolean(AppPrefs.KEY_WG_PROFILES_MIGRATED, true).commit();
        return seeded;
    }

    private static WireGuardProfile findByDedupKey(List<WireGuardProfile> profiles, String dedupKey) {
        if (TextUtils.isEmpty(dedupKey)) {
            return null;
        }
        for (WireGuardProfile profile : profiles) {
            if (profile != null && TextUtils.equals(profile.stableDedupKey(), dedupKey)) {
                return profile;
            }
        }
        return null;
    }

    private static int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(ProfileStoreSupport.trim(rawValue));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
