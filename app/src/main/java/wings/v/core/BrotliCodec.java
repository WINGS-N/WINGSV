package wings.v.core;

/**
 * Brotli поверх нативной библиотеки.
 *
 * <p>Кодек нужен обеим сторонам: приложение и разбирает присланные ссылки, и
 * собирает свои. Готового энкодера для Android нет, поэтому библиотека
 * собирается из исходников вместе с приложением.
 */
public final class BrotliCodec {

    /** Уровень сжатия. Одиннадцатый на этих данных даёт те же байты за время в полсотни раз большее */
    public static final int QUALITY = 5;

    private static volatile Boolean available;

    private BrotliCodec() {}

    /** Сообщает, поднялась ли нативная библиотека */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (BrotliCodec.class) {
            if (available == null) {
                boolean ok;
                try {
                    System.loadLibrary("wingsbrotli");
                    ok = true;
                } catch (Throwable error) {
                    ok = false;
                }
                available = ok;
            }
            return available;
        }
    }

    /** Сжимает, возвращая null когда библиотека недоступна или вход не поддался */
    public static byte[] compress(byte[] input) {
        if (input == null || !isAvailable()) {
            return null;
        }
        try {
            return nativeCompress(input, QUALITY);
        } catch (Throwable error) {
            return null;
        }
    }

    /** Распаковывает, возвращая null на битом входе */
    public static byte[] decompress(byte[] input) {
        if (input == null || !isAvailable()) {
            return null;
        }
        try {
            return nativeDecompress(input);
        } catch (Throwable error) {
            return null;
        }
    }

    private static native byte[] nativeCompress(byte[] input, int quality);

    private static native byte[] nativeDecompress(byte[] input);
}
