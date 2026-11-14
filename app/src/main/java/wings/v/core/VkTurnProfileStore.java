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
 * Profile store for the VK TURN backend. A VkTurnProfile composes its transport BY
 * REFERENCE - a transport kind plus the id of a WireGuardProfile or AmneziaProfile
 * - while the proxy fields and the VK TURN endpoint sit on the profile itself.
 *
 * <p>Holds the profile list, active id, favorites and traffic stats, plus a
 * one-time seed from the legacy flat KEY_ENDPOINT and VK TURN proxy keys, pointing
 * at the WG/AWG profile the matching backend migration seeded.
 *
 * <p>The flat keys are what the running tunnel reads, so applyActiveToPrefs
 * resolves the transport reference and writes the whole composition back onto them
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
     * Deduped by stableDedupKey - transport kind plus transport id plus endpoint -
     * so an identical profile is reused rather than stored twice. The active id
     * stays where it was
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
     * True when some stored VkTurnProfile references this transport - a
     * WireGuardProfile for kind "wg", an AmneziaProfile for "awg". The delete paths
     * of both transport stores ask first, because composition is by reference and a
     * deleted transport leaves the VK TURN profile pointing at nothing
     */
    public static boolean isTransportReferenced(Context context, String transportKind, String transportProfileId) {
        return !findVkTurnProfilesReferencing(context, transportKind, transportProfileId).isEmpty();
    }

    /**
     * Every stored VkTurnProfile pointing at this transport by id. The cascade
     * delete on either transport store wipes these first
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
     * Where the composition actually happens: transportProfileId is looked up in
     * its store (WireGuardProfileStore for "wg", AmneziaProfileStore for "awg"),
     * that transport's fields go onto the WG / AWG flat keys, and the VK TURN
     * endpoint and proxy fields go onto KEY_ENDPOINT and the VK TURN proxy keys
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
        // Transport first, onto the WG/AWG flat keys, then the VK TURN endpoint and
        // proxy fields on top
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
        // Threads, creds group size, the VK-ID auth mode, the session mode and the
        // browser fingerprint are device-global, NOT per-profile: project them here
        // and switching a profile resets what the user set globally
        ProfileStoreSupport.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_ENDPOINT, ProfileStoreSupport.trim(profile.vkTurnEndpoint))
            .putBoolean(AppPrefs.KEY_USE_UDP, profile.useUdp)
            .putBoolean(AppPrefs.KEY_NO_OBFUSCATION, profile.noObfuscation)
            .putBoolean(AppPrefs.KEY_MANUAL_CAPTCHA, profile.manualCaptcha)
            .putString(AppPrefs.KEY_CAPTCHA_AUTO_SOLVER, normalizeCaptchaAutoSolver(profile.captchaAutoSolver))
            .putBoolean(AppPrefs.KEY_VK_TURN_RESTART_ON_NETWORK_CHANGE, profile.restartOnNetworkChange)
            .putString(AppPrefs.KEY_VK_TURN_RUNTIME_MODE, ProxyRuntimeMode.fromPrefValue(profile.runtimeMode).prefValue)
            .putString(AppPrefs.KEY_VK_TURN_USER_DNS, ProfileStoreSupport.trim(profile.userDns))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_MODE, AppPrefs.normalizeWrapMode(profile.wrapMode))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_CIPHER, AppPrefs.normalizeWrapCipher(profile.wrapCipher))
            .putString(AppPrefs.KEY_VK_TURN_WRAP_KEY_HEX, ProfileStoreSupport.trim(profile.wrapKeyHex))
            .putBoolean(AppPrefs.KEY_VK_TURN_WRAP_SEND_KEY, profile.wrapSendKey)
            .putString(AppPrefs.KEY_DNS_MODE, AppPrefs.normalizeDnsMode(profile.dnsMode))
            .putString(
                AppPrefs.KEY_LOCAL_ENDPOINT,
                TextUtils.isEmpty(ProfileStoreSupport.trim(profile.localEndpoint))
                    ? DEFAULT_LOCAL_ENDPOINT
                    : ProfileStoreSupport.trim(profile.localEndpoint)
            )
            .putString(AppPrefs.KEY_TURN_HOST, ProfileStoreSupport.trim(profile.turnHost))
            .putString(AppPrefs.KEY_TURN_PORT, ProfileStoreSupport.trim(profile.turnPort))
            .putBoolean(AppPrefs.KEY_VK_TURN_WG_PROVISIONED, profile.wgProvisioned)
            .putString(AppPrefs.KEY_VK_TURN_PROVISION_CLIENT_ID, ProfileStoreSupport.trim(profile.provisionClientId))
            .putString(AppPrefs.KEY_VK_TURN_PROVISION_TOKEN, ProfileStoreSupport.trim(profile.provisionToken))
            .commit();
    }

    /**
     * The settings screen edits the composed flat keys, so on the way back the VK
     * TURN half lands on the active profile (id, title and transport reference stay
     * put) and the WG/AWG half lands on the referenced transport profile
     */
    public static VkTurnProfile updateActiveFromFlatPrefs(Context context) {
        VkTurnProfile active = getActiveProfile(context);
        if (active == null) {
            return null;
        }
        // The transport's flat-key edits go back into the referenced profile under
        // its own id, so the composition keeps pointing at it
        if (active.usesAmneziaTransport()) {
            AmneziaProfile transport = AmneziaProfileStore.getProfileById(context, active.transportProfileId);
            if (transport != null) {
                AmneziaProfileStore.replaceProfile(
                    context,
                    new AmneziaProfile(transport.id, transport.title, AmneziaStore.getEffectiveQuickConfig(context))
                );
            }
        } else {
            WireGuardProfile transport = WireGuardProfileStore.getProfileById(context, active.transportProfileId);
            if (transport != null) {
                WireGuardProfile flat = WireGuardProfileStore.readFlatProfile(context, transport.title);
                if (!flat.isEmpty()) {
                    WireGuardProfileStore.replaceProfile(
                        context,
                        new WireGuardProfile(
                            transport.id,
                            transport.title,
                            flat.privateKey,
                            flat.addresses,
                            flat.dns,
                            flat.mtu,
                            flat.publicKey,
                            flat.presharedKey,
                            flat.allowedIps,
                            flat.endpoint
                        )
                    );
                }
            }
        }
        VkTurnProfile updated = readFlatProfile(context, active.title, active.transportKind, active.transportProfileId);
        VkTurnProfile merged = new VkTurnProfile(
            active.id,
            active.title,
            active.transportKind,
            active.transportProfileId,
            // Flat KEY_ENDPOINT is the WG peer endpoint - the local TURN relay,
            // 127.0.0.1:9000 and friends - which the WG settings screen edits. The VK
            // TURN server endpoint is a VkTurnProfile-only field owned by the VK TURN
            // editor, so it survives the round-trip instead of being overwritten with
            // the local relay
            active.vkTurnEndpoint,
            updated.threads,
            updated.credsGroupSize,
            updated.useUdp,
            updated.noObfuscation,
            updated.manualCaptcha,
            updated.captchaAutoSolver,
            updated.vkAuthMode,
            updated.turnSessionMode,
            updated.dnsMode,
            updated.userDns,
            updated.runtimeMode,
            updated.restartOnNetworkChange,
            updated.wrapMode,
            updated.wrapCipher,
            updated.wrapKeyHex,
            updated.wrapSendKey,
            updated.localEndpoint,
            updated.turnHost,
            updated.turnPort
        );
        // A managed profile provisions its wg transport on connect, so its managed
        // identity (wgProvisioned plus provision client and token) has to survive the
        // fold-back - otherwise a trip through the settings screen turns it into a
        // plain profile and the provisioning credentials are gone
        if (active.isManaged()) {
            merged = merged.withManaged(active.provisionClientId, active.provisionToken);
        }
        if (!replaceProfile(context, merged)) {
            return null;
        }
        return merged;
    }

    /**
     * One-time seed of a single profile from the legacy flat KEY_ENDPOINT and VK
     * TURN proxy keys, gated by KEY_VK_TURN_PROFILES_MIGRATED. The transport comes
     * from the current VK TURN sub-backend (TunnelMode wg vs awg) and resolves to
     * the SAME profile the matching backend migration seeded, referenced by id and
     * never copied
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

    // Shared by the migration and the importer, so the two cannot drift apart
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
     * Import entry point: the importer has already written the flat VK TURN keys
     * plus the WG/AWG transport sub-config. The transport becomes a profile of its
     * own first (deduped, active in its backend), and only then does the
     * VkTurnProfile get built pointing at that id. A missing title is synthesized
     * from the endpoint
     */
    public static VkTurnProfile importActiveFromFlatPrefs(Context context, String title) {
        return importActiveFromFlatPrefs(context, title, "", "");
    }

    /**
     * Same import, plus the transport titles a single VK TURN share link carries:
     * wireGuardTitle / amneziaTitle name the embedded transport profile instead of
     * the generic "WireGuard" / "AmneziaWG" placeholder, which is what an empty one
     * falls back to
     */
    public static VkTurnProfile importActiveFromFlatPrefs(
        Context context,
        String title,
        String wireGuardTitle,
        String amneziaTitle
    ) {
        SharedPreferences prefs = ProfileStoreSupport.prefs(context);
        String endpoint = ProfileStoreSupport.trim(prefs.getString(AppPrefs.KEY_ENDPOINT, ""));
        if (TextUtils.isEmpty(endpoint)) {
            return null;
        }
        boolean awg = AppPrefs.getVkTurnTunnelMode(context) == TunnelMode.AMNEZIAWG;
        String transportKind = awg ? VkTurnProfile.TRANSPORT_KIND_AWG : VkTurnProfile.TRANSPORT_KIND_WG;
        String transportProfileId = importTransportProfileId(context, awg, wireGuardTitle, amneziaTitle);

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

    // Imports the transport sub-config the importer left in the flat keys and hands
    // back the stored id to reference. The title args come from a single-profile
    // share link; empty falls back to the generic placeholder
    private static String importTransportProfileId(
        Context context,
        boolean awg,
        String wireGuardTitle,
        String amneziaTitle
    ) {
        if (awg) {
            String amneziaName = ProfileStoreSupport.trim(amneziaTitle);
            AmneziaProfile transport = AmneziaProfileStore.importActiveFromFlatPrefs(
                context,
                TextUtils.isEmpty(amneziaName) ? "AmneziaWG" : amneziaName
            );
            return transport == null ? "" : transport.id;
        }
        String wireGuardName = ProfileStoreSupport.trim(wireGuardTitle);
        WireGuardProfile transport = WireGuardProfileStore.importActiveFromFlatPrefs(
            context,
            TextUtils.isEmpty(wireGuardName) ? "WireGuard" : wireGuardName
        );
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

    // Reuses the active WG/AWG profile, so the transport is shared with the
    // plain-backend list instead of duplicated. Seeds one when there is none
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

    /**
     * Swaps in the fetched list for one subscription: new profiles are added, the
     * vanished ones are pruned, and anything from another subscription (or made by
     * hand) is left alone. Ids are reused by stableDedupKey from the previous
     * snapshot of THIS subscription, so the active id and traffic stats survive a
     * refresh.
     *
     * <p>The caller syncs the embedded transports into their own stores BEFORE
     * calling, otherwise every transportProfileId here resolves to nothing
     */
    public static void syncSubscriptionProfiles(Context context, String subscriptionId, List<VkTurnProfile> fetched) {
        String subId = ProfileStoreSupport.trim(subscriptionId);
        if (TextUtils.isEmpty(subId)) {
            return;
        }
        List<VkTurnProfile> current = getProfiles(context);
        Map<String, String> reuseIds = new LinkedHashMap<>();
        ArrayList<VkTurnProfile> result = new ArrayList<>();
        for (VkTurnProfile profile : current) {
            if (profile == null) {
                continue;
            }
            if (TextUtils.equals(subId, profile.subscriptionId)) {
                reuseIds.putIfAbsent(profile.stableDedupKey(), profile.id);
            } else {
                result.add(profile);
            }
        }
        LinkedHashMap<String, VkTurnProfile> fetchedByKey = new LinkedHashMap<>();
        if (fetched != null) {
            for (VkTurnProfile profile : fetched) {
                if (profile == null || TextUtils.isEmpty(profile.id)) {
                    continue;
                }
                VkTurnProfile tagged = TextUtils.equals(subId, profile.subscriptionId)
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
        VkTurnProfile active = getActiveProfile(context);
        if (active != null && TextUtils.equals(subId, active.subscriptionId)) {
            applyActiveToPrefs(context);
        }
    }

    public static List<String> subscriptionIdsInUse(Context context) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (VkTurnProfile profile : getProfiles(context)) {
            if (profile != null && !TextUtils.isEmpty(profile.subscriptionId)) {
                ids.add(profile.subscriptionId);
            }
        }
        return new ArrayList<>(ids);
    }

    private static VkTurnProfile copyWithId(VkTurnProfile profile, String newId) {
        return new VkTurnProfile(
            newId,
            profile.title,
            profile.transportKind,
            profile.transportProfileId,
            profile.vkTurnEndpoint,
            profile.threads,
            profile.credsGroupSize,
            profile.useUdp,
            profile.noObfuscation,
            profile.manualCaptcha,
            profile.captchaAutoSolver,
            profile.vkAuthMode,
            profile.turnSessionMode,
            profile.dnsMode,
            profile.userDns,
            profile.runtimeMode,
            profile.restartOnNetworkChange,
            profile.wrapMode,
            profile.wrapCipher,
            profile.wrapKeyHex,
            profile.wrapSendKey,
            profile.localEndpoint,
            profile.turnHost,
            profile.turnPort,
            profile.subscriptionId,
            profile.subscriptionTitle
        );
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
        if ("v1".equals(normalized) || "v2".equals(normalized) || "bypass".equals(normalized)) {
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
