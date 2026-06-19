package be.mygod.vpnhotspot.root.daemon

import android.os.RemoteException
import java.io.IOException

class DaemonException(
    val report: DaemonErrorReport,
    private val callId: Long? = null,
    private val daemonClassName: String = "vpnhotspotd",
    cause: Throwable = ReportException(report, daemonClassName),
) : RemoteException(report.toExceptionMessage()) {
    companion object {
        private fun DaemonErrorReport.toExceptionMessage() = buildString {
            append(context).append(": ").append(message)
            if (errno != null && "(os error $errno)" !in message) {
                append(" (errno=").append(errno).append(')')
            }
            append(" [")
            if (errno == null || kind != "Uncategorized") append(kind).append(" at ")
            append(file_).append(':').append(line).append(':')
                .append(column).append(", pid=").append(pid).append(']')
        }
    }

    private class ReportException(report: DaemonErrorReport, daemonClassName: String) :
        IOException(report.toExceptionMessage()) {
        init {
            stackTrace = arrayOf(StackTraceElement(daemonClassName, report.context, report.file_, report.line))
        }
    }

    init {
        initCause(cause)
    }

    fun withCurrentTrace() = DaemonException(report, callId, daemonClassName, cause = this)
}
