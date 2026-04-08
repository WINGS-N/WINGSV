package wings.v.core;

import android.net.Uri;
import android.text.TextUtils;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class VlessLinkParser {

    private VlessLinkParser() {}

    public static XrayProfile parseProfile(String rawLink, String subscriptionId, String subscriptionTitle) {
        String normalized = rawLink == null ? "" : rawLink.trim();
        if (TextUtils.isEmpty(normalized) || !normalized.startsWith("vless://")) {
            return null;
        }
        try {
            Uri uri = Uri.parse(normalized);
            String encodedAuthority = uri.getEncodedAuthority();
            if (TextUtils.isEmpty(encodedAuthority)) {
                return null;
            }
            int atIndex = encodedAuthority.lastIndexOf('@');
            String hostPort = atIndex >= 0 ? encodedAuthority.substring(atIndex + 1) : encodedAuthority;
            int separator = hostPort.lastIndexOf(':');
            if (separator <= 0 || separator >= hostPort.length() - 1) {
                return null;
            }
            String host = hostPort.substring(0, separator).trim();
            int port = Integer.parseInt(hostPort.substring(separator + 1).trim());
            String fragment = uri.getEncodedFragment();
            String title = TextUtils.isEmpty(fragment)
                ? host + ":" + port
                : URLDecoder.decode(fragment, StandardCharsets.UTF_8.name());
            return new XrayProfile(
                UUID.randomUUID().toString(),
                title,
                normalized,
                subscriptionId,
                subscriptionTitle,
                host,
                port
            );
        } catch (Exception ignored) {
            return null;
        }
    }
}
