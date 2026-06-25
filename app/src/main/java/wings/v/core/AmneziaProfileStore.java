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
 * Per-profile store for the AmneziaWG backend, mirroring XrayStore's profile
 * API. Phase 1 of multi-profile support: persists the profile list, active id,
 * favorites and traffic stats, plus a one-time migration that seeds a single
 * profile from the legacy KEY_AWG_QUICK_CONFIG / structured prefs. The flat
 * config keys remain the source of truth for the running tunnel until later
 * phases wire the UI; applyActiveToPrefs writes the active profile back via
 * AmneziaStore.applyRawConfig.
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
public final class AmneziaProfileStore {

    private AmneziaProfileStore() {}

    public static List<AmneziaProfile> getProfiles(Context context) {
        ArrayList<AmneziaProfile> result = new ArrayList<>();
        JSONArray array = ProfileStoreSupport.readArray(context, AppPrefs.KEY_AWG_PROFILES_JSON);
        for (int index = 0; index < array.length(); index++) {
            AmneziaProfile profile = AmneziaProfile.fromJson(array.optJSONObject(index));
            if (profile != null && !profile.isEmpty()) {
                result.add(profile);
            }
        }
        return result;
    }

    public static void setProfiles(Context context, List<AmneziaProfile> profiles) {
        JSONArray array = new JSONArray();
        Map<String, AmneziaProfile> deduped = new LinkedHashMap<>();
        if (profiles != null) {
            for (AmneziaProfile profile : profiles) {
                if (profile == null || profile.isEmpty()) {
                    continue;
                }
                deduped.put(profile.id, profile);
            }
        }
        for (AmneziaProfile profile : deduped.values()) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {}
        }
        ProfileStoreSupport.prefs(context).edit().putString(AppPrefs.KEY_AWG_PROFILES_JSON, array.toString()).commit();
        ProfileStoreSupport.pruneTrafficStats(context, AppPrefs.KEY_AWG_PROFILE_TRAFFIC_JSON, deduped.keySet());
    }

    public static boolean replaceProfile(Context context, AmneziaProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id) || profile.isEmpty()) {
            return false;
        }
        ArrayList<AmneziaProfile> profiles = new ArrayList<>(getProfiles(context));
        for (int index = 0; index < profiles.size(); index++) {
            AmneziaProfile candidate = profiles.get(index);
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

    public static AmneziaProfile getProfileById(Context context, String profileId) {
        if (TextUtils.isEmpty(ProfileStoreSupport.trim(profileId))) {
            return null;
        }
        for (AmneziaProfile profile : getProfiles(context)) {
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
        if (VkTurnProfileStore.isTransportReferenced(context, VkTurnProfile.TRANSPORT_KIND_AWG, trimmedId)) {
            return false;
        }
        ArrayList<AmneziaProfile> profiles = new ArrayList<>(getProfiles(context));
        boolean removed = false;
        for (int index = profiles.size() - 1; index >= 0; index--) {
            AmneziaProfile candidate = profiles.get(index);
            if (candidate != null && TextUtils.equals(candidate.id, trimmedId)) {
                profiles.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        setProfiles(context, profiles);
        ProfileStoreSupport.setFavorite(context, AppPrefs.KEY_AWG_FAVORITE_PROFILE_IDS, trimmedId, false);
        if (TextUtils.equals(getActiveProfileId(context), trimmedId)) {
            setActiveProfileId(context, "");
            ensureActivePresent(context);
        }
        return true;
    }

    public static String getActiveProfileId(Context context) {
        return ProfileStoreSupport.getActiveProfileId(context, AppPrefs.KEY_AWG_ACTIVE_PROFILE_ID);
    }

    public static void setActiveProfileId(Context context, String profileId) {
        ProfileStoreSupport.setActiveProfileId(context, AppPrefs.KEY_AWG_ACTIVE_PROFILE_ID, profileId);
    }

    public static AmneziaProfile getActiveProfile(Context context) {
        AmneziaProfile profile = getProfileById(context, getActiveProfileId(context));
        if (profile != null) {
            return profile;
        }
        return ensureActivePresent(context);
    }

    public static AmneziaProfile ensureActivePresent(Context context) {
        AmneziaProfile active = getProfileById(context, getActiveProfileId(context));
        if (active != null) {
            return active;
        }
        List<AmneziaProfile> profiles = getProfiles(context);
        if (profiles.isEmpty()) {
            return null;
        }
        AmneziaProfile first = profiles.get(0);
        setActiveProfileId(context, first.id);
        return first;
    }

    public static Set<String> getFavoriteProfileIds(Context context) {
        return ProfileStoreSupport.getFavoriteProfileIds(context, AppPrefs.KEY_AWG_FAVORITE_PROFILE_IDS);
    }

    public static boolean isFavorite(Context context, String profileId) {
        return ProfileStoreSupport.isFavorite(context, AppPrefs.KEY_AWG_FAVORITE_PROFILE_IDS, profileId);
    }

    public static boolean toggleFavorite(Context context, String profileId) {
        return ProfileStoreSupport.toggleFavorite(context, AppPrefs.KEY_AWG_FAVORITE_PROFILE_IDS, profileId);
    }

    public static Map<String, XrayStore.ProfileTrafficStats> getProfileTrafficStatsMap(Context context) {
        return ProfileStoreSupport.getTrafficStatsMap(context, AppPrefs.KEY_AWG_PROFILE_TRAFFIC_JSON);
    }

    public static void addProfileTrafficDelta(Context context, String profileId, long rxDelta, long txDelta) {
        ProfileStoreSupport.addTrafficDelta(
            context,
            AppPrefs.KEY_AWG_PROFILE_TRAFFIC_JSON,
            profileId,
            rxDelta,
            txDelta
        );
    }

    public static void resetProfileTrafficStats(Context context, Collection<String> profileIds) {
        ProfileStoreSupport.resetTrafficStats(context, AppPrefs.KEY_AWG_PROFILE_TRAFFIC_JSON, profileIds);
    }

    /**
     * Writes the active profile's raw awg-quick config back into the legacy flat
     * config keys via AmneziaStore.applyRawConfig so the existing single-config
     * tunnel keeps reading them. No-op when there is no active profile.
     */
    public static void applyActiveToPrefs(Context context) {
        AmneziaProfile profile = getActiveProfile(context);
        if (profile == null) {
            return;
        }
        applyProfileToPrefs(context, profile);
    }

    static void applyProfileToPrefs(Context context, AmneziaProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return;
        }
        try {
            AmneziaStore.applyRawConfig(context, profile.quickConfig);
        } catch (Exception ignored) {}
    }

    /**
     * One-time migration: seeds a single profile from the effective awg-quick
     * config when it is non-empty. Idempotent and gated by the
     * KEY_AWG_PROFILES_MIGRATED flag. Returns the seeded (or pre-existing
     * active) profile, or null when there was nothing to migrate.
     */
    public static AmneziaProfile migrateFromFlatPrefs(Context context) {
        android.content.SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        if (prefs.getBoolean(AppPrefs.KEY_AWG_PROFILES_MIGRATED, false)) {
            return getActiveProfile(context);
        }
        String quickConfig = AmneziaStore.getEffectiveQuickConfig(context);
        AmneziaProfile flat = new AmneziaProfile(null, "AmneziaWG", quickConfig);
        if (flat.isEmpty()) {
            prefs.edit().putBoolean(AppPrefs.KEY_AWG_PROFILES_MIGRATED, true).commit();
            return null;
        }
        ArrayList<AmneziaProfile> profiles = new ArrayList<>(getProfiles(context));
        AmneziaProfile existing = findByDedupKey(profiles, flat.stableDedupKey());
        AmneziaProfile seeded = existing != null ? existing : flat;
        if (existing == null) {
            profiles.add(flat);
            setProfiles(context, profiles);
        }
        setActiveProfileId(context, seeded.id);
        prefs.edit().putBoolean(AppPrefs.KEY_AWG_PROFILES_MIGRATED, true).commit();
        return seeded;
    }

    private static AmneziaProfile findByDedupKey(List<AmneziaProfile> profiles, String dedupKey) {
        if (TextUtils.isEmpty(dedupKey)) {
            return null;
        }
        for (AmneziaProfile profile : profiles) {
            if (profile != null && TextUtils.equals(profile.stableDedupKey(), dedupKey)) {
                return profile;
            }
        }
        return null;
    }
}
