package wings.v.core;

import android.content.Context;
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Ключ, которым устройство подписывает расписки о полученном трафике.
 *
 * <p>Свои цифры нода рисует сама, поэтому платить по ним нельзя: завысил и
 * получил бабки за воздух. Расписку подписывает клиент, ключа у ноды нет, и
 * подделать её она не может при всём желании.
 *
 * <p>Симметричная крипта тут не годится в принципе: тем же ключом, которым
 * подписали, сервер и подделает. Нужна именно асимметрия.
 *
 * <p>Ed25519 тащим из bouncycastle, а не из системы: в Android он завёлся
 * только на API 33, а мы живём с 26.
 */
public final class FederationKey {

    /**
     * Домен подписи, байт в байт как на сервере. Нулевой байт на конце тут не
     * для красоты: он отделяет расписку от всего прочего, что этим ключом
     * когда либо подпишут, чтоб одно нельзя было предъявить вместо другого
     */
    private static final byte[] DOMAIN = ("wingsv-fed-receipt-v1" + '\u0000').getBytes(StandardCharsets.UTF_8);

    private FederationKey() {}

    /** Приватная половина. Родится при первом обращении и больше не меняется */
    @NonNull
    private static Ed25519PrivateKeyParameters privateKey(@NonNull Context context) {
        String stored = AppPrefs.prefs(context).getString(AppPrefs.KEY_FEDERATION_SIGNING_KEY, "");
        if (!stored.isEmpty()) {
            byte[] raw = Base64.decode(stored, Base64.NO_WRAP);
            if (raw.length == Ed25519PrivateKeyParameters.KEY_SIZE) {
                return new Ed25519PrivateKeyParameters(raw, 0);
            }
        }
        byte[] seed = new byte[Ed25519PrivateKeyParameters.KEY_SIZE];
        new SecureRandom().nextBytes(seed);
        AppPrefs.prefs(context)
            .edit()
            .putString(AppPrefs.KEY_FEDERATION_SIGNING_KEY, Base64.encodeToString(seed, Base64.NO_WRAP))
            .apply();
        return new Ed25519PrivateKeyParameters(seed, 0);
    }

    /** Публичная половина в base64. Её и отдаём серверу, приватную - никогда */
    @NonNull
    public static String publicKey(@NonNull Context context) {
        Ed25519PublicKeyParameters pub = privateKey(context).generatePublicKey();
        return Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP);
    }

    /** Сносит ключ нахуй: после выхода из аккаунта он уже ничей */
    public static void forget(@NonNull Context context) {
        AppPrefs.prefs(context).edit().remove(AppPrefs.KEY_FEDERATION_SIGNING_KEY).apply();
    }

    /**
     * Подписывает расписку. Байты лепятся ровно так же, как их лепит сервер:
     * фиксированная ширина полей и префикс домена. Гонять тут protobuf нельзя,
     * он не канонический, и два кодировщика высрут разные байты для одного и
     * того же сообщения, а подпись развалится на ровном месте.
     */
    @Nullable
    public static String sign(
        @NonNull Context context,
        @NonNull String clientId,
        @NonNull String nodeId,
        @NonNull String transport,
        @NonNull String nonce,
        long windowStartUnix,
        long windowEndUnix,
        long payloadUpBytes,
        long payloadDownBytes
    ) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffer.write(DOMAIN);
            appendField(buffer, clientId);
            appendField(buffer, nodeId);
            appendField(buffer, transport);
            appendField(buffer, nonce);
            appendNumber(buffer, windowStartUnix);
            appendNumber(buffer, windowEndUnix);
            appendNumber(buffer, payloadUpBytes);
            appendNumber(buffer, payloadDownBytes);

            byte[] message = buffer.toByteArray();
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, privateKey(context));
            signer.update(message, 0, message.length);
            return Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP);
        } catch (Exception error) {
            return null;
        }
    }

    private static void appendField(ByteArrayOutputStream buffer, String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        appendNumber(buffer, raw.length);
        buffer.write(raw, 0, raw.length);
    }

    private static void appendNumber(ByteArrayOutputStream buffer, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            buffer.write((int) ((value >>> shift) & 0xFF));
        }
    }
}
