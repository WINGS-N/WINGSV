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
 * Profile store for the WireGuard backend: profile list, active id, favorites,
 * traffic stats, plus a one-time seed from the legacy flat KEY_WG_* keys.
 *
 * <p>The flat keys are what the running tunnel reads, so applyActiveToPrefs has to
 * project the active profile back onto them
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

    /**
     * Deduped by stableDedupKey: an identical profile is reused rather than stored
     * twice. The active id stays where it was
     */
    public static WireGuardProfile addImportedProfile(Context context, WireGuardProfile profile) {
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        ArrayList<WireGuardProfile> profiles = new ArrayList<>(getProfiles(context));
        WireGuardProfile existing = findByDedupKey(profiles, profile.stableDedupKey());
        if (existing != null) {
            return existing;
        }
        profiles.add(profile);
        setProfiles(context, profiles);
        return profile;
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
        // A VkTurnProfile points at this transport by id, so pulling it out from
        // under that profile is not allowed
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

    /**
     * Opt-in cascade delete, called once the user confirms the warning modal:
     * plain deleteProfile refuses to drop a transport a VkTurnProfile points at,
     * this one takes the dependents down with it. If the active profile is among
     * them, a new active is picked and applyActiveToPrefs re-projects the flat keys
     */
    public static boolean deleteProfileCascade(Context context, String wgProfileId) {
        String trimmedId = ProfileStoreSupport.trim(wgProfileId);
        if (TextUtils.isEmpty(trimmedId)) {
            return false;
        }
        boolean removedDependents = false;
        for (VkTurnProfile dependent : VkTurnProfileStore.findVkTurnProfilesReferencing(
            context,
            VkTurnProfile.TRANSPORT_KIND_WG,
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

    /** Projects the active profile onto the flat KEY_WG_* keys the tunnel reads */
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

    /**
     * Import entry point: the importer has already written the flat KEY_WG_* keys,
     * so this picks them up as a new profile and makes it active
     */
    public static WireGuardProfile importActiveFromFlatPrefs(Context context, String title) {
        WireGuardProfile flat = readFlatProfile(context, title);
        WireGuardProfile stored = addImportedProfile(context, flat);
        if (stored == null) {
            return null;
        }
        setActiveProfileId(context, stored.id);
        applyActiveToPrefs(context);
        return stored;
    }

    /**
     * The settings screen edits the flat KEY_WG_* keys, so on the way back those
     * edits get folded into the active profile - keeping its id and title, and with
     * them its stats and favorite flag. Otherwise the flat keys and the stored
     * profile drift apart
     */
    public static WireGuardProfile updateActiveFromFlatPrefs(Context context) {
        WireGuardProfile active = getActiveProfile(context);
        if (active == null) {
            return null;
        }
        WireGuardProfile updated = readFlatProfile(context, active.title);
        if (updated.isEmpty()) {
            return null;
        }
        WireGuardProfile merged = new WireGuardProfile(
            active.id,
            active.title,
            updated.privateKey,
            updated.addresses,
            updated.dns,
            updated.mtu,
            updated.publicKey,
            updated.presharedKey,
            updated.allowedIps,
            updated.endpoint
        );
        if (!replaceProfile(context, merged)) {
            return null;
        }
        return merged;
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
     * One-time seed of a single profile from the legacy flat KEY_WG_* keys, gated
     * by KEY_WG_PROFILES_MIGRATED so it is idempotent
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

    /**
     * Swaps in the fetched list for one subscription: new profiles are added, the
     * vanished ones are pruned, and anything from another subscription (or made by
     * hand) is left alone. Ids are reused by stableDedupKey from the previous
     * snapshot of THIS subscription, so the active id and traffic stats survive a
     * refresh
     */
    public static void syncSubscriptionProfiles(
        Context context,
        String subscriptionId,
        List<WireGuardProfile> fetched
    ) {
        String subId = ProfileStoreSupport.trim(subscriptionId);
        if (TextUtils.isEmpty(subId)) {
            return;
        }
        List<WireGuardProfile> current = getProfiles(context);
        Map<String, String> reuseIds = new LinkedHashMap<>();
        ArrayList<WireGuardProfile> result = new ArrayList<>();
        for (WireGuardProfile profile : current) {
            if (profile == null) {
                continue;
            }
            if (TextUtils.equals(subId, profile.subscriptionId)) {
                reuseIds.putIfAbsent(profile.stableDedupKey(), profile.id);
            } else {
                result.add(profile);
            }
        }
        LinkedHashMap<String, WireGuardProfile> fetchedByKey = new LinkedHashMap<>();
        if (fetched != null) {
            for (WireGuardProfile profile : fetched) {
                if (profile == null || profile.isEmpty()) {
                    continue;
                }
                WireGuardProfile tagged = TextUtils.equals(subId, profile.subscriptionId)
                    ? profile
                    : profile.withSubscription(subId, profile.subscriptionTitle);
                String reused = reuseIds.get(tagged.stableDedupKey());
                if (!TextUtils.isEmpty(reused) && !TextUtils.equals(reused, tagged.id)) {
                    tagged = copyWithId(tagged, reused);
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
        WireGuardProfile active = getActiveProfile(context);
        if (active != null && TextUtils.equals(subId, active.subscriptionId)) {
            applyActiveToPrefs(context);
        }
    }

    public static List<String> subscriptionIdsInUse(Context context) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (WireGuardProfile profile : getProfiles(context)) {
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
        WireGuardProfile existing = findByDedupKey(getProfiles(context), dedupKey);
        return existing == null ? "" : existing.id;
    }

    private static WireGuardProfile copyWithId(WireGuardProfile profile, String newId) {
        return new WireGuardProfile(
            newId,
            profile.title,
            profile.privateKey,
            profile.addresses,
            profile.dns,
            profile.mtu,
            profile.publicKey,
            profile.presharedKey,
            profile.allowedIps,
            profile.endpoint,
            profile.subscriptionId,
            profile.subscriptionTitle
        );
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
