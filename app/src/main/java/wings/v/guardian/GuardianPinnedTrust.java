package wings.v.guardian;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS trust for a panel that has no publicly trusted certificate.
 *
 * <p>A deployment reachable only by bare IP cannot get a certificate from any
 * public CA, so the enrollment link carries the SPKI pin of the panel's own CA
 * and the chain is accepted when it contains a certificate matching that pin.
 * Hostname verification is left alone: the panel issues its leaf with the host
 * (including a bare IP) in the SAN, so the standard check still applies and this
 * only replaces the trust anchor, not the identity check.
 */
final class GuardianPinnedTrust {

    // SHA-512/256 rather than SHA-256: same 256-bit strength and the same 32 bytes
    // on the wire, without SHA-256's length-extension shape.
    private static final String PIN_DIGEST = "SHA-512/256";

    private GuardianPinnedTrust() {}

    /**
     * Builds a socket factory that trusts only chains containing one of pins.
     * Returns null when there is nothing to pin, leaving the caller on the system
     * trust store.
     */
    @Nullable
    static SSLSocketFactory socketFactory(@NonNull List<byte[]> pins) throws GeneralSecurityException {
        List<byte[]> usable = new ArrayList<>();
        for (byte[] pin : pins) {
            if (pin != null && pin.length > 0) {
                usable.add(pin);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new javax.net.ssl.TrustManager[] { new PinnedTrustManager(usable) }, null);
        return context.getSocketFactory();
    }

    private static final class PinnedTrustManager implements X509TrustManager {

        private final List<byte[]> pins;

        PinnedTrustManager(List<byte[]> pins) {
            this.pins = pins;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client authentication is not supported");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("empty certificate chain");
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(PIN_DIGEST);
            } catch (NoSuchAlgorithmException error) {
                // Fail closed: without the digest there is no way to tell a pinned
                // panel from any other server presenting a self-signed chain.
                throw new CertificateException("pin digest " + PIN_DIGEST + " unavailable", error);
            }
            for (X509Certificate certificate : chain) {
                if (certificate == null) {
                    continue;
                }
                digest.reset();
                byte[] actual = digest.digest(certificate.getPublicKey().getEncoded());
                for (byte[] pin : pins) {
                    if (MessageDigest.isEqual(actual, pin)) {
                        return;
                    }
                }
            }
            throw new CertificateException("no certificate in the chain matches a pinned panel CA");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
