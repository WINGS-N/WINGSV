package wings.v.core;

import android.system.ErrnoException;
import android.system.Os;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Crash-safe file writes. The store classes used to write configs by truncating
// the target file in place (new FileOutputStream(target, false)), so a process
// kill mid-write left the file empty or partial and the next read reset that
// store to defaults - one of the ways app data appeared to be wiped. These
// helpers write to a sibling temp file, fsync it, then atomically rename it over
// the target, so a reader only ever sees the old or the new full content.
public final class AtomicFiles {

    private static final int BUFFER_SIZE = 8192;

    private AtomicFiles() {}

    public static void writeUtf8(@NonNull File target, @NonNull String value) throws IOException {
        writeBytes(target, value.getBytes(StandardCharsets.UTF_8));
    }

    public static void writeBytes(@NonNull File target, @NonNull byte[] bytes) throws IOException {
        File temp = beginTemp(target);
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        commit(temp, target);
    }

    public static void replaceFrom(@NonNull InputStream source, @NonNull File target) throws IOException {
        File temp = beginTemp(target);
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read = source.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
                read = source.read(buffer);
            }
            output.flush();
            output.getFD().sync();
        }
        commit(temp, target);
    }

    private static File beginTemp(@NonNull File target) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return new File(parent, target.getName() + ".tmp");
    }

    private static void commit(@NonNull File temp, @NonNull File target) throws IOException {
        try {
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException error) {
            // Fallback for the rare filesystem without an atomic rename. delete()
            // first because renameTo() does not replace an existing file on every
            // platform; this leaves a small non-atomic window but is only reached
            // when Os.rename is unavailable.
            if (target.exists() && !target.delete()) {
                temp.delete();
                throw new IOException("could not replace " + target, error);
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IOException("atomic rename failed for " + target, error);
            }
        }
    }
}
