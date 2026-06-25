package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

/**
 * Per-profile store for the VK TURN backend, mirroring XrayStore's profile API.
 * A VkTurnProfile composes its transport BY REFERENCE: it carries a transport
 * kind plus the id of a WireGuardProfile or AmneziaProfile. The proxy fields and
 * the VK TURN endpoint live on the VkTurnProfile itself.
 *
 * Phase 1 of multi-profile support: persists the profile list, active id,
 * favorites and traffic stats, plus a one-time migration that seeds a single
 * profile from the legacy flat KEY_ENDPOINT + VK TURN proxy keys, referencing
 * the WG/AWG transport profile seeded by the matching backend migration. The
 * flat keys remain the source of truth for the running tunnel; applyActiveToPrefs
 * resolves the transport reference and writes everything back to the flat keys.
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
        "PMD.GodClass",
        "PMD.ExcessiveMethodLength",
    }
)
public final class VkTurnProfileStore {

    private static final String DEFAULT_LOCAL_ENDPOINT = "127.0.0.1:9000";

    private VkTurnProfileStore() {}

    public static List<VkTurnProfile> getProfiles(Context context) {
        ArrayList<VkTurnProfile> result = new ArrayList<>();
        JSONArray array = ProfileStoreSupport.readArray(context, AppPrefs.KEY_VK_TURN_PROFILES_JSON);
        for (int index = 0; index < array.length(); index++) {
            VkTurnProfile profile = VkTurnProfile.fromJson(array.optJSONObject(index));
            if (profile != null) {
                result.add(profile);
            }
        }
        return result;
    }

    public static void setProfiles(Context context, List<VkTurnProfile> profiles) {
        JSONArray array = new JSONArray();
        Map<String, VkTurnProfile> deduped = new LinkedHashMap<>();
        if (profiles != null) {
            for (VkTurnProfile profile : profiles) {
                if (profile == null) {
                    continue;
                }
                deduped.put(profile.id, profile);
            }
        }
        for (VkTurnProfile profile : deduped.values()) {
            try {
                array.put(profile.toJson());
            } catch (Exception ignored) {}
        }
        ProfileStoreSupport.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_VK_TURN_PROFILES_JSON, array.toString())
            .commit();
        ProfileStoreSupport.pruneTrafficStats(context, AppPrefs.KEY_VK_TURN_PROFILE_TRAFFIC_JSON, deduped.keySet());
    }

    public static boolean replaceProfile(Context context, VkTurnProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id)) {
            return false;
        }
        ArrayList<VkTurnProfile> profiles = new ArrayList<>(getProfiles(context));
        for (int index = 0; index < profiles.size(); index++) {
            VkTurnProfile candidate = profiles.get(index);
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
     * Adds an imported VK TURN profile to the list, deduped by stableDedupKey
     * (transport kind + transport id + endpoint): when an identical profile
     * already exists it is reused (and returned) instead of duplicating;
     * otherwise the supplied profile is appended. Returns the stored profile
     * (existing or newly added), or null when the candidate id is empty. Mirrors
     * Xray's add-don't-replace import; does not change the active id.
     */
    public static VkTurnProfile addImportedProfile(Context context, VkTurnProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.id)) {
            return null;
        }
        ArrayList<VkTurnProfile> profiles = new ArrayList<>(getProfiles(context));
        VkTurnProfile existing = findByDedupKey(profiles, profile.stableDedupKey());
        if (existing != null) {
            return existing;
        }
        profiles.add(profile);
        setProfiles(context, profiles);
        return profile;
    }

    public static VkTurnProfile getProfileById(Context context, String profileId) {
        if (TextUtils.isEmpty(ProfileStoreSupport.trim(profileId))) {
            return null;
        }
        for (VkTurnProfile profile : getProfiles(context)) {
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
        ArrayList<VkTurnProfile> profiles = new ArrayList<>(getProfiles(context));
        boolean removed = false;
        for (int index = profiles.size() - 1; index >= 0; index--) {
            VkTurnProfile candidate = profiles.get(index);
            if (candidate != null && TextUtils.equals(candidate.id, trimmedId)) {
                profiles.remove(index);
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        setProfiles(context, profiles);
        ProfileStoreSupport.setFavorite(context, AppPrefs.KEY_VK_TURN_FAVORITE_PROFILE_IDS, trimmedId, false);
        if (TextUtils.equals(getActiveProfileId(context), trimmedId)) {
            setActiveProfileId(context, "");
            ensureActivePresent(context);
        }
        return true;
    }

    /**
     * True when any stored VkTurnProfile references the given transport (a
     * WireGuardProfile when kind is "wg", an AmneziaProfile when kind is "awg").
     * Callers (WireGuardProfileStore.deleteProfile / AmneziaProfileStore.delete-
     * Profile) use this to refuse deleting a transport that a VK TURN profile
     * still points at, since composition is by reference.
     */
    public static boolean isTransportReferenced(Context context, String transportKind, String transportProfileId) {
        return !findVkTurnProfilesReferencing(context, transportKind, transportProfileId).isEmpty();
    }

    /**
     * All stored VkTurnProfiles that reference the given transport (a
     * WireGuardProfile when kind is "wg", an AmneziaProfile when kind is "awg")
     * by id. The cascade-delete helpers on WireGuardProfileStore /
     * AmneziaProfileStore use this to remove every dependent VK TURN profile
     * before the shared transport is deleted.
     */
    public static List<VkTurnProfile> findVkTurnProfilesReferencing(
        Context context,
        String transportKind,
        String transportProfileId
    ) {
        ArrayList<VkTurnProfile> result = new ArrayList<>();
        String normalizedKind = VkTurnProfile.normalizeTransportKind(transportKind);
        String normalizedId = ProfileStoreSupport.trim(transportProfileId);
        if (TextUtils.isEmpty(normalizedId)) {
            return result;
        }
        for (VkTurnProfile profile : getProfiles(context)) {
            if (
                profile != null &&
                TextUtils.equals(profile.transportKind, normalizedKind) &&
                TextUtils.equals(profile.transportProfileId, normalizedId)
            ) {
                result.add(profile);
            }
        }
        return result;
    }

    public static String getActiveProfileId(Context context) {
        return ProfileStoreSupport.getActiveProfileId(context, AppPrefs.KEY_VK_TURN_ACTIVE_PROFILE_ID);
    }

    public static void setActiveProfileId(Context context, String profileId) {
        ProfileStoreSupport.setActiveProfileId(context, AppPrefs.KEY_VK_TURN_ACTIVE_PROFILE_ID, profileId);
    }

    public static VkTurnProfile getActiveProfile(Context context) {
        VkTurnProfile profile = getProfileById(context, getActiveProfileId(context));
        if (profile != null) {
            return profile;
        }
        return ensureActivePresent(context);
    }

    public static VkTurnProfile ensureActivePresent(Context context) {
        VkTurnProfile active = getProfileById(context, getActiveProfileId(context));
        if (active != null) {
            return active;
        }
        List<VkTurnProfile> profiles = getProfiles(context);
        if (profiles.isEmpty()) {
            return null;
        }
        VkTurnProfile first = profiles.get(0);
        setActiveProfileId(context, first.id);
        return first;
    }

    public static Set<String> getFavoriteProfileIds(Context context) {
        return ProfileStoreSupport.getFavoriteProfileIds(context, AppPrefs.KEY_VK_TURN_FAVORITE_PROFILE_IDS);
    }

    public static boolean isFavorite(Context context, String profileId) {
        return ProfileStoreSupport.isFavorite(context, AppPrefs.KEY_VK_TURN_FAVORITE_PROFILE_IDS, profileId);
    }

    public static boolean toggleFavorite(Context context, String profileId) {
        return ProfileStoreSupport.toggleFavorite(context, AppPrefs.KEY_VK_TURN_FAVORITE_PROFILE_IDS, profileId);
    }

    public static Map<String, XrayStore.ProfileTrafficStats> getProfileTrafficStatsMap(Context context) {
        return ProfileStoreSupport.getTrafficStatsMap(context, AppPrefs.KEY_VK_TURN_PROFILE_TRAFFIC_JSON);
    }

    public static void addProfileTrafficDelta(Context context, String profileId, long rxDelta, long txDelta) {
        ProfileStoreSupport.addTrafficDelta(
            context,
            AppPrefs.KEY_VK_TURN_PROFILE_TRAFFIC_JSON,
            profileId,
            rxDelta,
            txDelta
        );
    }

    public static void resetProfileTrafficStats(Context context, Collection<String> profileIds) {
        ProfileStoreSupport.resetTrafficStats(context, AppPrefs.KEY_VK_TURN_PROFILE_TRAFFIC_JSON, profileIds);
    }

    /**
     * Applies the active VK TURN profile to the legacy flat keys. Resolution of
     * the transport reference happens here: transportProfileId is looked up in
     * the matching transport store (WireGuardProfileStore for "wg",
     * AmneziaProfileStore for "awg"); that transport's fields are written to the
     * WG / AWG flat keys, then the VK TURN endpoint and proxy fields are written
     * to KEY_ENDPOINT and the VK TURN proxy keys. This is the composition that
     * happens at apply-time. No-op when there is no active profile.
     */
    public static void applyActiveToPrefs(Context context) {
        VkTurnProfile profile = getActiveProfile(context);
        if (profile == null) {
            return;
        }
        applyProfileToPrefs(context, profile);
    }

    static void applyProfileToPrefs(Context context, VkTurnProfile profile) {
        if (profile == null) {
            return;
        }
        // Resolve the transport by reference and apply its fields to the WG/AWG
        // flat keys first, then layer the VK TURN endpoint + proxy fields.
        if (profile.usesAmneziaTransport()) {
            AmneziaProfile transport = AmneziaProfileStore.getProfileById(context, profile.transportProfileId);
            if (transport != null) {
                AmneziaProfileStore.applyProfileToPrefs(context, transport);
            }
        } else {
            WireGuardProfile transport = WireGuardProfileStore.getProfileById(context, profile.transportProfileId);
            if (transport != null) {
                WireGuardProfileStore.applyProfileToPrefs(context, transport);
            }
        }
        ProfileStoreSupport.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_ENDPOINT, ProfileStoreSupport.trim(profile.vkTurnEndpoint))
            .putString(AppPrefs.KEY_THREADS, String.valueOf(profile.threads > 0 ? profile.threads : 24))
            .putString(
                AppPrefs.KEY_CREDS_GROUP_SIZE,
                String.valueOf(profile.credsGroupSize > 0 ? profile.credsGroupSize : 12)
            )
            .putBoolean(AppPrefs.KEY_USE_UDP, profile.useUdp)
            .putBoolean(AppPrefs.KEY_NO_OBFUSCATION, profile.noObfuscation)
            .putBoolean(AppPrefs.KEY_MANUAL_CAPTCHA, profile.manualCaptcha)
            .putString(AppPrefs.KEY_CAPTCHA_AUTO_SOLVER, normalizeCaptchaAutoSolver(profile.captchaAutoSolver))
            .putBoolean(
                AppPrefs.KEY_VK_AUTH_MODE,
                AppPrefs.VK_AUTH_MODE_ACCOUNT.equals(AppPrefs.normalizeVkAuthMode(profile.vkAuthMode))
            )
            .putBoolean(AppPrefs.KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE, profile.restartOnNetworkChange)
            .putString(AppPrefs.KEY_VK_TURN_RUNTIME_MODE, ProxyRuntimeMode.fromPrefValue(profile.runtimeMode).prefValue)
            .putString(AppPrefs.KEY_VK_TURN_USER_DNS, ProfileStoreSupport.trim(profile.userDns))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_MODE, AppPrefs.normalizeWrapMode(profile.wrapMode))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_CIPHER, AppPrefs.normalizeWrapCipher(profile.wrapCipher))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_KEY_HEX, ProfileStoreSupport.trim(profile.wrapKeyHex))
            .putBoolean(AppPrefs.KEY_VK_TURN_WRAP_SEND_KEY, profile.wrapSendKey)
            .putString(AppPrefs.KEY_TURN_SESSION_MODE, ProfileStoreSupport.trim(profile.turnSessionMode))
            .putString(AppPrefs.KEY_DNS_MODE, AppPrefs.normalizeDnsMode(profile.dnsMode))
            .putString(
                AppPrefs.KEY_LOCAL_ENDPOINT,
                TextUtils.isEmpty(ProfileStoreSupport.trim(profile.localEndpoint))
                    ? DEFAULT_LOCAL_ENDPOINT
                    : ProfileStoreSupport.trim(profile.localEndpoint)
            )
            .putString(AppPrefs.KEY_TURN_HOST, ProfileStoreSupport.trim(profile.turnHost))
            .putString(AppPrefs.KEY_TURN_PORT, ProfileStoreSupport.trim(profile.turnPort))
            .commit();
    }

    /**
     * One-time migration: seeds a single profile from the legacy flat
     * KEY_ENDPOINT + VK TURN proxy keys when the endpoint is non-empty. The
     * transport is chosen from the current VK TURN sub-backend (TunnelMode wg vs
     * awg) and resolved to the SAME WireGuardProfile/AmneziaProfile that the
     * matching backend migration seeded; it is referenced by id, never copied.
     * Idempotent and gated by the KEY_VK_TURN_PROFILES_MIGRATED flag.
     */
    public static VkTurnProfile migrateFromFlatPrefs(Context context) {
        SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        if (prefs.getBoolean(AppPrefs.KEY_VK_TURN_PROFILES_MIGRATED, false)) {
            return getActiveProfile(context);
        }
        String endpoint = ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_ENDPOINT, ""));
        if (TextUtils.isEmpty(endpoint)) {
            prefs.edit().putBoolean(AppPrefs.KEY_VK_TURN_PROFILES_MIGRATED, true).commit();
            return null;
        }
        boolean awg = AppPrefs.getVkTurnTunnelMode(context) == TunnelMode.AMNEZIAWG;
        String transportKind = awg ? VkTurnProfile.TRANSPORT_KIND_AWG : VkTurnProfile.TRANSPORT_KIND_WG;
        String transportProfileId = resolveTransportProfileId(context, awg);

        VkTurnProfile profile = readFlatProfile(context, "VK TURN", transportKind, transportProfileId);

        ArrayList<VkTurnProfile> profiles = new ArrayList<>(getProfiles(context));
        VkTurnProfile existing = findByDedupKey(profiles, profile.stableDedupKey());
        VkTurnProfile seeded = existing != null ? existing : profile;
        if (existing == null) {
            profiles.add(profile);
            setProfiles(context, profiles);
        }
        setActiveProfileId(context, seeded.id);
        prefs.edit().putBoolean(AppPrefs.KEY_VK_TURN_PROFILES_MIGRATED, true).commit();
        return seeded;
    }

    // Reads the flat KEY_ENDPOINT + VK TURN proxy keys into a VkTurnProfile that
    // references the given transport. Shared by the migration and the importer so
    // the two stay in lockstep.
    static VkTurnProfile readFlatProfile(
        Context context,
        String title,
        String transportKind,
        String transportProfileId
    ) {
        SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        return new VkTurnProfile(
            null,
            title,
            transportKind,
            transportProfileId,
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_ENDPOINT, "")),
            parseInt(prefs.getString(AppPrefs.KEY_THREADS, "24"), 24),
            parseInt(prefs.getString(AppPrefs.KEY_CREDS_GROUP_SIZE, "12"), 12),
            prefs.getBoolean(AppPrefs.KEY_USE_UDP, true),
            prefs.getBoolean(AppPrefs.KEY_NO_OBFUSCATION, false),
            prefs.getBoolean(AppPrefs.KEY_MANUAL_CAPTCHA, false),
            normalizeCaptchaAutoSolver(
                prefs.getString(AppPrefs.KEY_CAPTCHA_AUTO_SOLVER, AppPrefs.CAPTCHA_AUTO_SOLVER_DEFAULT)
            ),
            prefs.getBoolean(AppPrefs.KEY_VK_AUTH_MODE, false)
                ? AppPrefs.VK_AUTH_MODE_ACCOUNT
                : AppPrefs.VK_AUTH_MODE_ANONYMOUS,
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_TURN_SESSION_MODE, "mainline")),
            AppPrefs.getDnsMode(context),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_VK_TURN_USER_DNS, "")),
            ProxyRuntimeMode.fromPrefValue(
                prefs.getString(AppPrefs.KEY_VK_TURN_RUNTIME_MODE, ProxyRuntimeMode.VPN.prefValue)
            ).prefValue,
            prefs.getBoolean(AppPrefs.KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE, true),
            AppPrefs.normalizeWrapMode(prefs.getString(AppPrefs.KEY_VK_TURN_WRAP_MODE, "preferred")),
            AppPrefs.normalizeWrapCipher(prefs.getString(AppPrefs.KEY_VK_TURN_WRAP_CIPHER, "srtp-aes-gcm")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_VK_TURN_WRAP_KEY_HEX, "")),
            prefs.getBoolean(AppPrefs.KEY_VK_TURN_WRAP_SEND_KEY, true),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_LOCAL_ENDPOINT, DEFAULT_LOCAL_ENDPOINT)),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_TURN_HOST, "")),
            ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_TURN_PORT, ""))
        );
    }

    /**
     * Import-as-profile entry point: the importer has already written the flat
     * VK TURN keys plus the WG/AWG transport sub-config. This first imports that
     * transport as its own profile (deduped, made active in its backend), then
     * builds a VkTurnProfile referencing that transport id from the flat VK TURN
     * keys, adds it deduped, makes it active and re-projects everything back onto
     * the flat keys. The title is synthesized from the endpoint when none is
     * given. Returns the active profile, or null when the endpoint is empty.
     */
    public static VkTurnProfile importActiveFromFlatPrefs(Context context, String title) {
        SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        String endpoint = ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_ENDPOINT, ""));
        if (TextUtils.isEmpty(endpoint)) {
            return null;
        }
        boolean awg = AppPrefs.getVkTurnTunnelMode(context) == TunnelMode.AMNEZIAWG;
        String transportKind = awg ? VkTurnProfile.TRANSPORT_KIND_AWG : VkTurnProfile.TRANSPORT_KIND_WG;
        String transportProfileId = importTransportProfileId(context, awg);

        String resolvedTitle = TextUtils.isEmpty(ProfileStoreSupport.trim(title))
            ? synthesizeTitle(context, endpoint)
            : title;
        VkTurnProfile profile = readFlatProfile(context, resolvedTitle, transportKind, transportProfileId);
        VkTurnProfile stored = addImportedProfile(context, profile);
        if (stored == null) {
            return null;
        }
        setActiveProfileId(context, stored.id);
        applyActiveToPrefs(context);
        return stored;
    }

    // Imports (deduped) the WG/AWG transport sub-config that the importer wrote to
    // the flat keys and returns the stored transport id to reference.
    private static String importTransportProfileId(Context context, boolean awg) {
        if (awg) {
            AmneziaProfile transport = AmneziaProfileStore.importActiveFromFlatPrefs(context, "AmneziaWG");
            return transport == null ? "" : transport.id;
        }
        WireGuardProfile transport = WireGuardProfileStore.importActiveFromFlatPrefs(context, "WireGuard");
        return transport == null ? "" : transport.id;
    }

    private static String synthesizeTitle(Context context, String endpoint) {
        String host = ProfileStoreSupport.trim(endpoint);
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        if (TextUtils.isEmpty(host)) {
            host = "VK TURN";
        }
        return host + " #" + (getProfiles(context).size() + 1);
    }

    // Resolves (and seeds if necessary) the transport profile id that the VK TURN
    // profile should reference. Reuses the active WG/AWG profile so the same
    // transport is shared with the plain-backend list rather than duplicated.
    private static String resolveTransportProfileId(Context context, boolean awg) {
        if (awg) {
            AmneziaProfile transport = AmneziaProfileStore.getActiveProfile(context);
            if (transport == null) {
                transport = AmneziaProfileStore.migrateFromFlatPrefs(context);
            }
            return transport == null ? "" : transport.id;
        }
        WireGuardProfile transport = WireGuardProfileStore.getActiveProfile(context);
        if (transport == null) {
            transport = WireGuardProfileStore.migrateFromFlatPrefs(context);
        }
        return transport == null ? "" : transport.id;
    }

    private static VkTurnProfile findByDedupKey(List<VkTurnProfile> profiles, String dedupKey) {
        if (TextUtils.isEmpty(dedupKey)) {
            return null;
        }
        for (VkTurnProfile profile : profiles) {
            if (profile != null && TextUtils.equals(profile.stableDedupKey(), dedupKey)) {
                return profile;
            }
        }
        return null;
    }

    private static String normalizeCaptchaAutoSolver(String value) {
        String normalized = ProfileStoreSupport.trim(value).toLowerCase(java.util.Locale.ROOT);
        if ("v1".equals(normalized) || "v2".equals(normalized)) {
            return normalized;
        }
        return AppPrefs.CAPTCHA_AUTO_SOLVER_DEFAULT;
    }

    private static int parseInt(String rawValue, int fallback) {
        try {
            return Integer.parseInt(ProfileStoreSupport.trim(rawValue));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
