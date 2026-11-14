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
 * Profile store for the AmneziaWG backend: profile list, active id, favorites,
 * traffic stats, plus a one-time seed from the legacy KEY_AWG_QUICK_CONFIG /
 * structured prefs.
 *
 * <p>The flat config keys are what the running tunnel reads, so applyActiveToPrefs
 * pushes the active profile back through AmneziaStore.applyRawConfig
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

    /**
     * Deduped by stableDedupKey: an identical profile is reused rather than stored
     * twice. The active id stays where it was
     */
    public static AmneziaProfile addImportedProfile(Context context, AmneziaProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        ArrayList<AmneziaProfile> profiles = new ArrayList<>(getProfiles(context));
        AmneziaProfile existing = findByDedupKey(profiles, profile.stableDedupKey());
        if (existing != null) {
            return existing;
        }
        profiles.add(profile);
        setProfiles(context, profiles);
        return profile;
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

    /**
     * Opt-in cascade delete, called once the user confirms the warning modal:
     * plain deleteProfile refuses to drop a transport a VkTurnProfile points at,
     * this one takes the dependents down with it. If the active profile is among
     * them, a new active is picked and applyActiveToPrefs re-projects the flat keys
     */
    public static boolean deleteProfileCascade(Context context, String awgProfileId) {
        String trimmedId = ProfileStoreSupport.trim(awgProfileId);
        if (TextUtils.isEmpty(trimmedId)) {
            return false;
        }
        boolean removedDependents = false;
        for (VkTurnProfile dependent : VkTurnProfileStore.findVkTurnProfilesReferencing(
            context,
            VkTurnProfile.TRANSPORT_KIND_AWG,
            trimmedId
        )) {
            if (dependent != null && VkTurnProfileStore.deleteProfile(context, dependent.id)) {
                removedDependents = true;
            }
        }
        if (removedDependents) {
            VkTurnProfileStore.ensureActivePresent(context);
            VkTurnProfileStore.applyActiveToPrefs(context);
        }
        // Nothing references the transport now, so plain deleteProfile goes through
        return deleteProfile(context, trimmedId);
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
     * Pushes the active profile's raw awg-quick config into the flat config keys
     * through AmneziaStore.applyRawConfig, which is where the tunnel reads it
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
     * Import entry point: the importer has already written the effective awg-quick
     * config, so this picks it up as a new profile and makes it active
     */
    public static AmneziaProfile importActiveFromFlatPrefs(Context context, String title) {
        AmneziaProfile flat = new AmneziaProfile(null, title, AmneziaStore.getEffectiveQuickConfig(context));
        AmneziaProfile stored = addImportedProfile(context, flat);
        if (stored == null) {
            return null;
        }
        setActiveProfileId(context, stored.id);
        applyActiveToPrefs(context);
        return stored;
    }

    /**
     * The settings screen edits the flat structured and raw keys, so on the way
     * back those edits get folded into the active profile - keeping its id and
     * title, and with them its stats and favorite flag
     */
    public static AmneziaProfile updateActiveFromFlatPrefs(Context context) {
        AmneziaProfile active = getActiveProfile(context);
        if (active == null) {
            return null;
        }
        AmneziaProfile updated = new AmneziaProfile(
            active.id,
            active.title,
            AmneziaStore.getEffectiveQuickConfig(context)
        );
        if (updated.isEmpty() || !replaceProfile(context, updated)) {
            return null;
        }
        return updated;
    }

    /**
     * One-time seed of a single profile from the effective awg-quick config, gated
     * by KEY_AWG_PROFILES_MIGRATED so it is idempotent
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

    /**
     * Swaps in the fetched list for one subscription: new profiles are added, the
     * vanished ones are pruned, and anything from another subscription (or made by
     * hand) is left alone. Ids are reused by stableDedupKey from the previous
     * snapshot of THIS subscription, so the active id and traffic stats survive a
     * refresh
     */
    public static void syncSubscriptionProfiles(Context context, String subscriptionId, List<AmneziaProfile> fetched) {
        String subId = ProfileStoreSupport.trim(subscriptionId);
        if (TextUtils.isEmpty(subId)) {
            return;
        }
        List<AmneziaProfile> current = getProfiles(context);
        Map<String, String> reuseIds = new LinkedHashMap<>();
        ArrayList<AmneziaProfile> result = new ArrayList<>();
        for (AmneziaProfile profile : current) {
            if (profile == null) {
                continue;
            }
            if (TextUtils.equals(subId, profile.subscriptionId)) {
                reuseIds.putIfAbsent(profile.stableDedupKey(), profile.id);
            } else {
                result.add(profile);
            }
        }
        LinkedHashMap<String, AmneziaProfile> fetchedByKey = new LinkedHashMap<>();
        if (fetched != null) {
            for (AmneziaProfile profile : fetched) {
                if (profile == null || profile.isEmpty()) {
                    continue;
                }
                AmneziaProfile tagged = TextUtils.equals(subId, profile.subscriptionId)
                    ? profile
                    : profile.withSubscription(subId, profile.subscriptionTitle);
                String reused = reuseIds.get(tagged.stableDedupKey());
                if (!TextUtils.isEmpty(reused) && !TextUtils.equals(reused, tagged.id)) {
                    tagged = new AmneziaProfile(
                        reused,
                        tagged.title,
                        tagged.quickConfig,
                        tagged.subscriptionId,
                        tagged.subscriptionTitle
                    );
                }
                fetchedByKey.put(tagged.stableDedupKey(), tagged);
            }
        }
        // Nothing stored and nothing fetched: skip rewriting the whole store on every
        // refresh of an unrelated subscription
        if (reuseIds.isEmpty() && fetchedByKey.isEmpty()) {
            return;
        }
        result.addAll(fetchedByKey.values());
        setProfiles(context, result);
        ensureActivePresent(context);
        // Плоские ключи читает рантайм, и без проекции обновлённый профиль лежит
        // в сторе, а туннель поднимается по прежнему адресу - до тех пор, пока
        // человек не переключит профиль руками
        AmneziaProfile active = getActiveProfile(context);
        if (active != null && TextUtils.equals(subId, active.subscriptionId)) {
            applyActiveToPrefs(context);
        }
    }

    public static List<String> subscriptionIdsInUse(Context context) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (AmneziaProfile profile : getProfiles(context)) {
            if (profile != null && !TextUtils.isEmpty(profile.subscriptionId)) {
                ids.add(profile.subscriptionId);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Resolves a VK TURN profile's transport reference to a stable id, once that
     * transport has been synced into this store
     */
    public static String idForDedupKey(Context context, String dedupKey) {
        AmneziaProfile existing = findByDedupKey(getProfiles(context), dedupKey);
        return existing == null ? "" : existing.id;
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
