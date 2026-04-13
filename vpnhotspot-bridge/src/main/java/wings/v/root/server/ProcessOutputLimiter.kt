package wings.v.root.server

import java.io.BufferedReader

private const val READ_BUFFER_SIZE = 4096
private const val MAX_CAPTURED_OUTPUT_CHARS = 64 * 1024
private const val TRUNCATED_SUFFIX = "\n...[truncated]"

internal fun BufferedReader.readLimitedProcessOutput(maxChars: Int = MAX_CAPTURED_OUTPUT_CHARS): String {
    val safeLimit = maxChars.coerceAtLeast(0)
    val buffer = CharArray(READ_BUFFER_SIZE)
    val result = StringBuilder(minOf(safeLimit, READ_BUFFER_SIZE))
    var remaining = safeLimit
    var truncated = false

    while (true) {
        val read = read(buffer)
        if (read < 0) {
            break
        }
        if (remaining > 0) {
            val chunkLength = minOf(read, remaining)
            result.append(buffer, 0, chunkLength)
            remaining -= chunkLength
            if (chunkLength < read) {
                truncated = true
            }
        } else {
            truncated = true
        }
    }

    if (truncated) {
        result.append(TRUNCATED_SUFFIX)
    }
    return result.toString()
}
