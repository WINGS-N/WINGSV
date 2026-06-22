package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks IPv4/IPv6 literals for display in the UI when the user enabled the
 * "hide IP address" toggle. The first group/octet is kept and the rest is
 * replaced with bullets, while separators, ports and non-IP parts of the
 * string (hostnames, keys, CIDR prefix length) stay intact. DNS values are
 * intentionally not passed through here - the user asked to keep DNS visible.
 */
public final class IpMask {

    private static final char BULLET = '•';

    // IPv4: four dotted octets. Lookarounds keep it from grabbing a fragment of
    // a longer number.
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\d.])\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?![\\d.])"
    );

    // IPv6: two or more hex groups separated by colons (including the "::" run).
    // A bare ":51820" port (a single colon group) does not match.
    private static final Pattern IPV6 = Pattern.compile(
        "(?<![0-9A-Fa-f:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,}(?![0-9A-Fa-f:])"
    );

    private IpMask() {}

    public static String apply(Context context, String text) {
        if (TextUtils.isEmpty(text) || context == null || !AppPrefs.isHideIpEnabled(context)) {
            return text;
        }
        return mask(text);
    }

    public static String mask(String text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        String result = maskMatches(text, IPV4);
        result = maskMatches(result, IPV6);
        return result;
    }

    private static String maskMatches(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String token = matcher.group();
            String replacement = isExempt(token) ? token : maskToken(token);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // Loopback, unspecified и приватные/локальные адреса не секретны -
    // маскировать их бессмысленно. Маскируем только публичные/маршрутизируемые.
    private static boolean isExempt(String token) {
        if (token.indexOf('.') >= 0 && token.indexOf(':') < 0) {
            return isPrivateOrLocalV4(token);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if ("::1".equals(lower) || "::".equals(lower)) {
            // IPv6 loopback / unspecified.
            return true;
        }
        // IPv6 link-local fe80::/10 (fe8..feb) и unique-local fc00::/7 (fc/fd).
        return (
            lower.startsWith("fe8") ||
            lower.startsWith("fe9") ||
            lower.startsWith("fea") ||
            lower.startsWith("feb") ||
            lower.startsWith("fc") ||
            lower.startsWith("fd")
        );
    }

    private static boolean isPrivateOrLocalV4(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return false;
        }
        // 0.0.0.0/8 unspecified, 127/8 loopback, 10/8, 192.168/16, 172.16/12,
        // 169.254/16 link-local, 100.64/10 CGNAT.
        return (
            a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b >= 16 && b <= 31) ||
            (a == 169 && b == 254) ||
            (a == 100 && b >= 64 && b <= 127)
        );
    }

    // Keep the leading hex/digit run (first group), then turn every following
    // hex char into a bullet while keeping separators (dots, colons).
    private static String maskToken(String token) {
        int len = token.length();
        int i = 0;
        while (i < len && isHex(token.charAt(i))) {
            i++;
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(token, 0, i);
        for (; i < len; i++) {
            char c = token.charAt(i);
            sb.append(isHex(c) ? BULLET : c);
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
