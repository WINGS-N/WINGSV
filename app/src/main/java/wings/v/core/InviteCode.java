package wings.v.core;

import android.net.Uri;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Код приглашения, каким он приезжает из QR.
 *
 * <p>Форм две: ссылка на регистрацию, которую жрёт любая системная камера, и наша
 * схема с голым кодом. Голый HEX без префикса не принимается специально - иначе
 * сканер начнёт считать приглашением любую шестнадцатеричную строку, а их в
 * ссылках и ключах хватает.
 */
public final class InviteCode {

    /** Наша схема для голого кода: wingsv://invite/A1B2C3D4E5F60789 */
    private static final String SCHEME_PREFIX = "wingsv://invite/";

    /** Код - это HEX, и длину задаёт сервер: 16 знаков на восемь байт */
    private static final Pattern CODE = Pattern.compile("^[0-9A-Fa-f]{8,64}$");

    private InviteCode() {}

    /** Достаёт код из скана или возвращает null, когда это не приглашение */
    @Nullable
    public static String parse(@Nullable String scanned) {
        String text = scanned == null ? "" : scanned.trim();
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith(SCHEME_PREFIX)) {
            return normalize(text.substring(SCHEME_PREFIX.length()));
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                String invite = Uri.parse(text).getQueryParameter("invite");
                return normalize(invite);
            } catch (Exception broken) {
                return null;
            }
        }
        return null;
    }

    /** Ссылка на регистрацию: её понимает и чужая камера, и наш сканер */
    @NonNull
    public static String link(@NonNull String panelUrl, @NonNull String code) {
        return panelUrl + "/register?invite=" + code.trim().toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static String normalize(@Nullable String code) {
        String value = code == null ? "" : code.trim();
        if (!CODE.matcher(value).matches()) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
