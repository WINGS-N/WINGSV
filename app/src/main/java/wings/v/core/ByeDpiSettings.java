package wings.v.core;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
public final class ByeDpiSettings {

    public static final String DEFAULT_COMMAND_ARGS =
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1";
    private static final String DEFAULT_CONNECT_BIND_IP = "0.0.0.0";

    public enum DesyncMethod {
        NONE("none"),
        SPLIT("split"),
        DISORDER("disorder"),
        FAKE("fake"),
        OOB("oob"),
        DISOOB("disoob");

        public final String prefValue;

        DesyncMethod(String prefValue) {
            this.prefValue = prefValue;
        }

        @NonNull
        public static DesyncMethod fromPrefValue(@Nullable String value) {
            String normalized = trim(value).toLowerCase(Locale.US);
            for (DesyncMethod method : values()) {
                if (TextUtils.equals(method.prefValue, normalized)) {
                    return method;
                }
            }
            return OOB;
        }
    }

    public enum HostsMode {
        DISABLE("disable"),
        BLACKLIST("blacklist"),
        WHITELIST("whitelist");

        public final String prefValue;

        HostsMode(String prefValue) {
            this.prefValue = prefValue;
        }

        @NonNull
        public static HostsMode fromPrefValue(@Nullable String value) {
            String normalized = trim(value).toLowerCase(Locale.US);
            for (HostsMode mode : values()) {
                if (TextUtils.equals(mode.prefValue, normalized)) {
                    return mode;
                }
            }
            return DISABLE;
        }
    }

    public boolean launchOnXrayStart;
    public boolean useCommandLineSettings;
    public String proxyIp = "127.0.0.1";
    public int proxyPort = 1080;
    public boolean proxyAuthEnabled = true;
    public String proxyUsername = "";
    public String proxyPassword = "";
    public int maxConnections = 512;
    public int bufferSize = 16384;
    public int defaultTtl;
    public boolean noDomain;
    public boolean desyncHttp = true;
    public boolean desyncHttps = true;
    public boolean desyncUdp = true;
    public DesyncMethod desyncMethod = DesyncMethod.OOB;
    public int splitPosition = 1;
    public boolean splitAtHost;
    public int fakeTtl = 8;
    public String fakeSni = "www.iana.org";
    public String oobData = "a";
    public boolean hostMixedCase;
    public boolean domainMixedCase;
    public boolean hostRemoveSpaces;
    public boolean tlsRecordSplit = true;
    public int tlsRecordSplitPosition = 1;
    public boolean tlsRecordSplitAtSni = true;
    public HostsMode hostsMode = HostsMode.DISABLE;
    public String hostsBlacklist = "";
    public String hostsWhitelist = "";
    public boolean tcpFastOpen;
    public int udpFakeCount = 1;
    public boolean dropSack;
    public int fakeOffset;
    public String rawCommandArgs = DEFAULT_COMMAND_ARGS;
    public int proxyTestDelaySeconds = 1;
    public int proxyTestRequests = 1;
    public int proxyTestConcurrencyLimit = 20;
    public int proxyTestTimeoutSeconds = 5;
    public String proxyTestSni = "max.ru";
    public boolean proxyTestUseCustomStrategies;
    public String proxyTestCustomStrategies = "";

    @NonNull
    public List<String> buildRuntimeArguments(@Nullable String protectPath) {
        return useCommandLineSettings ? buildCommandArguments(protectPath) : buildUiArguments(protectPath);
    }

    @NonNull
    public String resolveRuntimeListenIp() {
        CommandAddress commandAddress = parseCommandAddress(rawCommandArgs);
        if (useCommandLineSettings && !TextUtils.isEmpty(commandAddress.ip)) {
            return commandAddress.ip;
        }
        String normalized = trim(proxyIp);
        return TextUtils.isEmpty(normalized) ? "127.0.0.1" : normalized;
    }

    public int resolveRuntimeListenPort() {
        CommandAddress commandAddress = parseCommandAddress(rawCommandArgs);
        if (useCommandLineSettings && commandAddress.port > 0) {
            return commandAddress.port;
        }
        return proxyPort > 0 ? proxyPort : 1080;
    }

    @NonNull
    public String resolveRuntimeDialHost() {
        String listenHost = resolveRuntimeListenIp();
        if (
            TextUtils.isEmpty(listenHost) ||
            TextUtils.equals(listenHost, "0.0.0.0") ||
            TextUtils.equals(listenHost, "::") ||
            TextUtils.equals(listenHost, "[::]")
        ) {
            return "127.0.0.1";
        }
        return listenHost;
    }

    @NonNull
    public String resolveRuntimeProxyUsername() {
        CommandProxyAuth commandProxyAuth = parseCommandProxyAuth(rawCommandArgs);
        if (useCommandLineSettings && !TextUtils.isEmpty(commandProxyAuth.username)) {
            return commandProxyAuth.username;
        }
        return trim(proxyUsername);
    }

    @NonNull
    public String resolveRuntimeProxyPassword() {
        CommandProxyAuth commandProxyAuth = parseCommandProxyAuth(rawCommandArgs);
        if (useCommandLineSettings && !TextUtils.isEmpty(commandProxyAuth.password)) {
            return commandProxyAuth.password;
        }
        return trim(proxyPassword);
    }

    @NonNull
    private List<String> buildCommandArguments(@Nullable String protectPath) {
        ArrayList<String> result = new ArrayList<>();
        result.add("ciadpi");

        List<String> splitArgs = ByeDpiShellUtils.shellSplit(trim(rawCommandArgs));
        CommandAddress commandAddress = parseCommandAddress(rawCommandArgs);
        boolean hasProtectPath = hasProtectPathArgument(splitArgs);
        boolean hasConnIp = hasConnIpArgument(splitArgs);
        boolean hasSocksUser = hasArgument(splitArgs, "--socks-user");
        boolean hasSocksPass = hasArgument(splitArgs, "--socks-pass");
        if (TextUtils.isEmpty(commandAddress.ip)) {
            result.add("--ip");
            result.add(resolveRuntimeListenIp());
        }
        if (commandAddress.port <= 0) {
            result.add("--port");
            result.add(String.valueOf(resolveRuntimeListenPort()));
        }
        if (!hasConnIp) {
            result.add("--conn-ip");
            result.add(DEFAULT_CONNECT_BIND_IP);
        }
        for (String token : splitArgs) {
            if (
                TextUtils.isEmpty(token) ||
                TextUtils.equals(token, "--help") ||
                TextUtils.equals(token, "--version") ||
                TextUtils.equals(token, "-h") ||
                TextUtils.equals(token, "-v")
            ) {
                continue;
            }
            result.add(token);
        }
        if (!TextUtils.isEmpty(protectPath) && !hasProtectPath) {
            result.add("--protect-path");
            result.add(protectPath);
        }
        if (proxyAuthEnabled) {
            if (!hasSocksUser && !TextUtils.isEmpty(resolveRuntimeProxyUsername())) {
                result.add("--socks-user");
                result.add(resolveRuntimeProxyUsername());
            }
            if (!hasSocksPass && !TextUtils.isEmpty(resolveRuntimeProxyPassword())) {
                result.add("--socks-pass");
                result.add(resolveRuntimeProxyPassword());
            }
        }
        return result;
    }

    @NonNull
    private List<String> buildUiArguments(@Nullable String protectPath) {
        ArrayList<String> result = new ArrayList<>();
        result.add("ciadpi");
        result.add("-i" + resolveRuntimeListenIp());
        result.add("-p" + resolveRuntimeListenPort());
        result.add("-I" + DEFAULT_CONNECT_BIND_IP);

        if (maxConnections > 0) {
            result.add("-c" + maxConnections);
        }
        if (bufferSize > 0) {
            result.add("-b" + bufferSize);
        }

        ArrayList<String> protocols = new ArrayList<>();
        if (desyncHttps) {
            protocols.add("t");
        }
        if (desyncHttp) {
            protocols.add("h");
        }

        String hostsValue = trim(hostsMode == HostsMode.BLACKLIST ? hostsBlacklist : hostsWhitelist);
        if (!TextUtils.isEmpty(hostsValue)) {
            String hostArg = ":" + hostsValue.replace('\n', ' ');
            if (hostsMode == HostsMode.BLACKLIST) {
                result.add("-H" + hostArg);
                result.add("-An");
                if (!protocols.isEmpty()) {
                    result.add("-K" + TextUtils.join(",", protocols));
                }
            } else if (hostsMode == HostsMode.WHITELIST) {
                if (!protocols.isEmpty()) {
                    result.add("-K" + TextUtils.join(",", protocols));
                }
                result.add("-H" + hostArg);
            }
        } else if (!protocols.isEmpty()) {
            result.add("-K" + TextUtils.join(",", protocols));
        }

        if (defaultTtl != 0) {
            result.add("-g" + defaultTtl);
        }
        if (noDomain) {
            result.add("-N");
        }

        if (splitPosition != 0) {
            String positionArgument = splitPosition + (splitAtHost ? "+h" : "");
            switch (desyncMethod) {
                case SPLIT:
                    result.add("-s" + positionArgument);
                    break;
                case DISORDER:
                    result.add("-d" + positionArgument);
                    break;
                case OOB:
                    result.add("-o" + positionArgument);
                    break;
                case DISOOB:
                    result.add("-q" + positionArgument);
                    break;
                case FAKE:
                    result.add("-f" + positionArgument);
                    break;
                case NONE:
                    break;
            }
        }

        if (desyncMethod == DesyncMethod.FAKE) {
            if (fakeTtl != 0) {
                result.add("-t" + fakeTtl);
            }
            if (!TextUtils.isEmpty(trim(fakeSni))) {
                result.add("-n" + trim(fakeSni));
            }
            if (fakeOffset != 0) {
                result.add("-O" + fakeOffset);
            }
        }

        if (desyncMethod == DesyncMethod.OOB || desyncMethod == DesyncMethod.DISOOB) {
            String oobCharValue = TextUtils.isEmpty(trim(oobData)) ? "a" : trim(oobData);
            result.add("-e" + (byte) oobCharValue.charAt(0));
        }

        ArrayList<String> httpMods = new ArrayList<>();
        if (hostMixedCase) {
            httpMods.add("h");
        }
        if (domainMixedCase) {
            httpMods.add("d");
        }
        if (hostRemoveSpaces) {
            httpMods.add("r");
        }
        if (!httpMods.isEmpty()) {
            result.add("-M" + TextUtils.join(",", httpMods));
        }

        if (tlsRecordSplit && tlsRecordSplitPosition != 0) {
            result.add("-r" + tlsRecordSplitPosition + (tlsRecordSplitAtSni ? "+s" : ""));
        }
        if (tcpFastOpen) {
            result.add("-F");
        }
        if (dropSack) {
            result.add("-Y");
        }

        result.add("-An");

        if (desyncUdp) {
            result.add("-Ku");
            if (udpFakeCount > 0) {
                result.add("-a" + udpFakeCount);
            }
            result.add("-An");
        }

        if (!TextUtils.isEmpty(protectPath)) {
            result.add("--protect-path");
            result.add(protectPath);
        }
        if (proxyAuthEnabled) {
            if (!TextUtils.isEmpty(resolveRuntimeProxyUsername())) {
                result.add("--socks-user");
                result.add(resolveRuntimeProxyUsername());
            }
            if (!TextUtils.isEmpty(resolveRuntimeProxyPassword())) {
                result.add("--socks-pass");
                result.add(resolveRuntimeProxyPassword());
            }
        }
        return result;
    }

    private boolean hasProtectPathArgument(@NonNull List<String> args) {
        for (String token : args) {
            String normalized = trim(token);
            if (
                TextUtils.equals(normalized, "--protect-path") ||
                TextUtils.equals(normalized, "-P") ||
                normalized.startsWith("-P")
            ) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConnIpArgument(@NonNull List<String> args) {
        for (String token : args) {
            String normalized = trim(token);
            if (
                TextUtils.equals(normalized, "--conn-ip") ||
                normalized.startsWith("--conn-ip=") ||
                TextUtils.equals(normalized, "-I") ||
                (normalized.startsWith("-I") && normalized.length() > 2)
            ) {
                return true;
            }
        }
        return false;
    }

    private boolean hasArgument(@NonNull List<String> args, @NonNull String longName) {
        for (String token : args) {
            String normalized = trim(token);
            if (TextUtils.equals(normalized, longName) || normalized.startsWith(longName + "=")) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private CommandAddress parseCommandAddress(@Nullable String rawArgs) {
        List<String> split = ByeDpiShellUtils.shellSplit(trim(rawArgs));
        String commandIp = "";
        int commandPort = 0;
        int index = 0;
        while (index < split.size()) {
            String token = trim(split.get(index));
            if (token.startsWith("-i") && token.length() > 2) {
                commandIp = trim(token.substring(2));
            } else if (TextUtils.equals(token, "--ip") && index + 1 < split.size()) {
                commandIp = trim(split.get(index + 1));
                index++;
            } else if (token.startsWith("-p") && token.length() > 2) {
                commandPort = parseInt(token.substring(2), 0);
            } else if (TextUtils.equals(token, "--port") && index + 1 < split.size()) {
                commandPort = parseInt(split.get(index + 1), 0);
                index++;
            }
            index++;
        }
        return new CommandAddress(commandIp, commandPort);
    }

    @NonNull
    private CommandProxyAuth parseCommandProxyAuth(@Nullable String rawArgs) {
        List<String> split = ByeDpiShellUtils.shellSplit(trim(rawArgs));
        String username = "";
        String password = "";
        int index = 0;
        while (index < split.size()) {
            String token = trim(split.get(index));
            if (token.startsWith("--socks-user=")) {
                username = trim(token.substring("--socks-user=".length()));
            } else if (TextUtils.equals(token, "--socks-user") && index + 1 < split.size()) {
                username = trim(split.get(index + 1));
                index++;
            } else if (token.startsWith("--socks-pass=")) {
                password = trim(token.substring("--socks-pass=".length()));
            } else if (TextUtils.equals(token, "--socks-pass") && index + 1 < split.size()) {
                password = trim(split.get(index + 1));
                index++;
            }
            index++;
        }
        return new CommandProxyAuth(username, password);
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

    private static final class CommandAddress {

        final String ip;
        final int port;

        CommandAddress(String ip, int port) {
            this.ip = trim(ip);
            this.port = Math.max(0, port);
        }
    }

    private static final class CommandProxyAuth {

        final String username;
        final String password;

        CommandProxyAuth(String username, String password) {
            this.username = trim(username);
            this.password = trim(password);
        }
    }
}
