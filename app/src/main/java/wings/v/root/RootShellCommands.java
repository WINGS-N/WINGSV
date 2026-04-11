package wings.v.root;

import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class RootShellCommands {

    private RootShellCommands() {}

    @SuppressWarnings("PMD.SystemPrintln")
    static void handle(String... args) throws Exception {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("Usage: shell <command>");
        }
        String command = TextUtils.join(" ", args).trim();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Usage: shell <command>");
        }
        Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        String output;
        try (InputStream inputStream = process.getInputStream()) {
            output = readFully(inputStream);
        }
        int exitCode = process.waitFor();
        if (!TextUtils.isEmpty(output)) {
            System.out.print(output);
        }
        if (exitCode != 0) {
            throw new IllegalStateException(
                TextUtils.isEmpty(output) ? "Shell command exited with code " + exitCode : output.trim()
            );
        }
    }

    private static String readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        read = inputStream.read(buffer);
        while (read != -1) {
            outputStream.write(buffer, 0, read);
            read = inputStream.read(buffer);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }
}
