package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings(
    {
        "PMD.AvoidUsingHardCodedIP",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class ByeDpiStore {

    public static final String KEY_OPEN_SETTINGS = "pref_open_bydpi_settings";
    public static final String KEY_AUTO_START_WITH_XRAY = "pref_bydpi_auto_start_with_xray";
    public static final String KEY_USE_COMMAND_SETTINGS = "pref_bydpi_use_command_settings";
    public static final String KEY_PROXY_IP = "pref_bydpi_proxy_ip";
    public static final String KEY_PROXY_PORT = "pref_bydpi_proxy_port";
    public static final String KEY_PROXY_AUTH_ENABLED = "pref_bydpi_proxy_auth_enabled";
    public static final String KEY_PROXY_USERNAME = "pref_bydpi_proxy_username";
    public static final String KEY_PROXY_PASSWORD = "pref_bydpi_proxy_password";
    public static final String KEY_MAX_CONNECTIONS = "pref_bydpi_max_connections";
    public static final String KEY_BUFFER_SIZE = "pref_bydpi_buffer_size";
    public static final String KEY_NO_DOMAIN = "pref_bydpi_no_domain";
    public static final String KEY_TCP_FAST_OPEN = "pref_bydpi_tcp_fast_open";
    public static final String KEY_HOSTS_MODE = "pref_bydpi_hosts_mode";
    public static final String KEY_HOSTS_BLACKLIST = "pref_bydpi_hosts_blacklist";
    public static final String KEY_HOSTS_WHITELIST = "pref_bydpi_hosts_whitelist";
    public static final String KEY_DEFAULT_TTL = "pref_bydpi_default_ttl";
    public static final String KEY_DESYNC_METHOD = "pref_bydpi_desync_method";
    public static final String KEY_SPLIT_POSITION = "pref_bydpi_split_position";
    public static final String KEY_SPLIT_AT_HOST = "pref_bydpi_split_at_host";
    public static final String KEY_DROP_SACK = "pref_bydpi_drop_sack";
    public static final String KEY_FAKE_TTL = "pref_bydpi_fake_ttl";
    public static final String KEY_FAKE_OFFSET = "pref_bydpi_fake_offset";
    public static final String KEY_FAKE_SNI = "pref_bydpi_fake_sni";
    public static final String KEY_OOB_DATA = "pref_bydpi_oob_data";
    public static final String KEY_DESYNC_HTTP = "pref_bydpi_desync_http";
    public static final String KEY_DESYNC_HTTPS = "pref_bydpi_desync_https";
    public static final String KEY_DESYNC_UDP = "pref_bydpi_desync_udp";
    public static final String KEY_HOST_MIXED_CASE = "pref_bydpi_host_mixed_case";
    public static final String KEY_DOMAIN_MIXED_CASE = "pref_bydpi_domain_mixed_case";
    public static final String KEY_HOST_REMOVE_SPACES = "pref_bydpi_host_remove_spaces";
    public static final String KEY_TLSREC_ENABLED = "pref_bydpi_tlsrec_enabled";
    public static final String KEY_TLSREC_POSITION = "pref_bydpi_tlsrec_position";
    public static final String KEY_TLSREC_AT_SNI = "pref_bydpi_tlsrec_at_sni";
    public static final String KEY_UDP_FAKE_COUNT = "pref_bydpi_udp_fake_count";
    public static final String KEY_CMD_ARGS = "pref_bydpi_cmd_args";
    public static final String KEY_PROXYTEST_DELAY = "pref_bydpi_proxytest_delay";
    public static final String KEY_PROXYTEST_REQUESTS = "pref_bydpi_proxytest_requests";
    public static final String KEY_PROXYTEST_LIMIT = "pref_bydpi_proxytest_limit";
    public static final String KEY_PROXYTEST_TIMEOUT = "pref_bydpi_proxytest_timeout";
    public static final String KEY_PROXYTEST_SNI = "pref_bydpi_proxytest_sni";
    public static final String KEY_PROXYTEST_USE_CUSTOM_STRATEGIES = "pref_bydpi_proxytest_use_custom_strategies";
    public static final String KEY_PROXYTEST_CUSTOM_STRATEGIES = "pref_bydpi_proxytest_custom_strategies";
    public static final String KEY_PROXYTEST_TARGETS = "pref_bydpi_proxytest_targets";
    public static final String KEY_PROXYTEST_OPEN_TARGETS = "pref_open_bydpi_proxytest_targets";
    public static final String KEY_PROXYTEST_OPEN_RUNNER = "pref_open_bydpi_proxytest_runner";

    private static final String STRATEGY_ASSET_PATH = "byedpi_proxytest_strategies.list";

    private ByeDpiStore() {}

    @NonNull
    public static ByeDpiSettings getSettings(@Nullable Context context) {
        ByeDpiSettings settings = new ByeDpiSettings();
        if (context == null) {
            return settings;
        }
        SharedPreferences prefs = prefs(context);
        SocksAuthCredentials.Pair credentials = SocksAuthCredentials.ensure(
            prefs,
            KEY_PROXY_USERNAME,
            KEY_PROXY_PASSWORD
        );
        settings.launchOnXrayStart = prefs.getBoolean(KEY_AUTO_START_WITH_XRAY, false);
        settings.useCommandLineSettings = prefs.getBoolean(KEY_USE_COMMAND_SETTINGS, false);
        settings.proxyIp = trim(prefs.getString(KEY_PROXY_IP, "127.0.0.1"));
        settings.proxyPort = parseInt(prefs.getString(KEY_PROXY_PORT, "1080"), 1080);
        settings.proxyAuthEnabled = prefs.getBoolean(KEY_PROXY_AUTH_ENABLED, true);
        settings.proxyUsername = credentials.username;
        settings.proxyPassword = credentials.password;
        settings.maxConnections = parseInt(prefs.getString(KEY_MAX_CONNECTIONS, "512"), 512);
        settings.bufferSize = parseInt(prefs.getString(KEY_BUFFER_SIZE, "16384"), 16384);
        settings.noDomain = prefs.getBoolean(KEY_NO_DOMAIN, false);
        settings.tcpFastOpen = prefs.getBoolean(KEY_TCP_FAST_OPEN, false);
        settings.hostsMode = ByeDpiSettings.HostsMode.fromPrefValue(prefs.getString(KEY_HOSTS_MODE, "disable"));
        settings.hostsBlacklist = trim(prefs.getString(KEY_HOSTS_BLACKLIST, ""));
        settings.hostsWhitelist = trim(prefs.getString(KEY_HOSTS_WHITELIST, ""));
        settings.defaultTtl = parseInt(prefs.getString(KEY_DEFAULT_TTL, "0"), 0);
        settings.desyncMethod = ByeDpiSettings.DesyncMethod.fromPrefValue(
            prefs.getString(KEY_DESYNC_METHOD, ByeDpiSettings.DesyncMethod.OOB.prefValue)
        );
        settings.splitPosition = parseInt(prefs.getString(KEY_SPLIT_POSITION, "1"), 1);
        settings.splitAtHost = prefs.getBoolean(KEY_SPLIT_AT_HOST, false);
        settings.dropSack = prefs.getBoolean(KEY_DROP_SACK, false);
        settings.fakeTtl = parseInt(prefs.getString(KEY_FAKE_TTL, "8"), 8);
        settings.fakeOffset = parseInt(prefs.getString(KEY_FAKE_OFFSET, "0"), 0);
        settings.fakeSni = trim(prefs.getString(KEY_FAKE_SNI, "www.iana.org"));
        settings.oobData = trim(prefs.getString(KEY_OOB_DATA, "a"));
        settings.desyncHttp = prefs.getBoolean(KEY_DESYNC_HTTP, true);
        settings.desyncHttps = prefs.getBoolean(KEY_DESYNC_HTTPS, true);
        settings.desyncUdp = prefs.getBoolean(KEY_DESYNC_UDP, true);
        settings.hostMixedCase = prefs.getBoolean(KEY_HOST_MIXED_CASE, false);
        settings.domainMixedCase = prefs.getBoolean(KEY_DOMAIN_MIXED_CASE, false);
        settings.hostRemoveSpaces = prefs.getBoolean(KEY_HOST_REMOVE_SPACES, false);
        settings.tlsRecordSplit = prefs.getBoolean(KEY_TLSREC_ENABLED, true);
        settings.tlsRecordSplitPosition = parseInt(prefs.getString(KEY_TLSREC_POSITION, "1"), 1);
        settings.tlsRecordSplitAtSni = prefs.getBoolean(KEY_TLSREC_AT_SNI, true);
        settings.udpFakeCount = parseInt(prefs.getString(KEY_UDP_FAKE_COUNT, "1"), 1);
        settings.rawCommandArgs = trim(prefs.getString(KEY_CMD_ARGS, ByeDpiSettings.DEFAULT_COMMAND_ARGS));
        settings.proxyTestDelaySeconds = parseInt(prefs.getString(KEY_PROXYTEST_DELAY, "1"), 1);
        settings.proxyTestRequests = parseInt(prefs.getString(KEY_PROXYTEST_REQUESTS, "1"), 1);
        settings.proxyTestConcurrencyLimit = parseInt(prefs.getString(KEY_PROXYTEST_LIMIT, "20"), 20);
        settings.proxyTestTimeoutSeconds = parseInt(prefs.getString(KEY_PROXYTEST_TIMEOUT, "5"), 5);
        settings.proxyTestSni = trim(prefs.getString(KEY_PROXYTEST_SNI, "max.ru"));
        settings.proxyTestUseCustomStrategies = prefs.getBoolean(KEY_PROXYTEST_USE_CUSTOM_STRATEGIES, false);
        settings.proxyTestCustomStrategies = trim(prefs.getString(KEY_PROXYTEST_CUSTOM_STRATEGIES, ""));
        return settings;
    }

    public static boolean isSettingsAvailable(@Nullable Context context) {
        return context != null && XrayStore.getBackendType(context) == BackendType.XRAY;
    }

    @NonNull
    public static List<String> getProxyTestTargets(@Nullable Context context) {
        return ByeDpiDomainListStore.getActiveDomains(context);
    }

    public static void saveProxyTestTargets(@Nullable Context context, @Nullable List<String> targets) {
        // legacy flat target storage is intentionally unused
    }

    @NonNull
    public static List<String> getProxyTestStrategies(@Nullable Context context) {
        ArrayList<String> result = new ArrayList<>();
        if (context == null) {
            return result;
        }
        ByeDpiSettings settings = getSettings(context);
        if (settings.proxyTestUseCustomStrategies && !TextUtils.isEmpty(settings.proxyTestCustomStrategies)) {
            for (String line : settings.proxyTestCustomStrategies.split("\n")) {
                String normalized = trim(line);
                if (!TextUtils.isEmpty(normalized)) {
                    result.add(replaceStrategyPlaceholders(normalized, settings.proxyTestSni));
                }
            }
            return result;
        }
        try (
            InputStream inputStream = context.getAssets().open(STRATEGY_ASSET_PATH);
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(inputStreamReader)
        ) {
            String line;
            line = reader.readLine();
            while (line != null) {
                String normalized = trim(line);
                if (!TextUtils.isEmpty(normalized)) {
                    result.add(replaceStrategyPlaceholders(normalized, settings.proxyTestSni));
                }
                line = reader.readLine();
            }
        } catch (java.io.IOException ignored) {}
        return result;
    }

    public static void applyStrategy(@Nullable Context context, @Nullable String command) {
        if (context == null) {
            return;
        }
        String normalized = trim(command);
        if (TextUtils.isEmpty(normalized)) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_USE_COMMAND_SETTINGS, true).putString(KEY_CMD_ARGS, normalized).commit();
    }

    @NonNull
    public static String buildTargetsSummary(@Nullable Context context) {
        return ByeDpiDomainListStore.buildActiveListsSummary(context);
    }

    private static String replaceStrategyPlaceholders(@NonNull String strategy, @Nullable String sni) {
        String normalizedSni = TextUtils.isEmpty(trim(sni)) ? "max.ru" : trim(sni);
        return strategy.replace("{sni}", normalizedSni);
    }

    @NonNull
    public static String normalizeTarget(@Nullable String value) {
        String normalized = trim(value);
        if (TextUtils.isEmpty(normalized)) {
            return "";
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        return normalized;
    }

    private static int parseInt(@Nullable String value, int defaultValue) {
        try {
            return Integer.parseInt(trim(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return AppPrefs.defaultSharedPreferences(context);
    }
}
