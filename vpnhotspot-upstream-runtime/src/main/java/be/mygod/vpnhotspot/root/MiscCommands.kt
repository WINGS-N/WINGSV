package be.mygod.vpnhotspot.root

import android.content.Context
import android.net.TetheringManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import android.provider.Settings
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.ParcelableInt
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.librootkotlinx.isEBADF
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.Routing.Companion.IP
import be.mygod.vpnhotspot.net.Routing.Companion.IPTABLES
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.net.VpnFirewallManager
import be.mygod.vpnhotspot.util.Services
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException

fun ProcessBuilder.fixPath(redirect: Boolean = false) = apply {
    environment().compute("PATH") { _, value ->
        if (value.isNullOrEmpty()) "/system/bin" else "$value:/system/bin"
    }
    redirectErrorStream(redirect)
}

class Dump(
    private val path: String,
    private val cacheDir: File = app.deviceStorage.codeCacheDir,
) : RootCommandNoResult {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        File(parcel.readString() ?: app.deviceStorage.codeCacheDir.absolutePath),
    )

    override suspend fun execute() = withContext(Dispatchers.IO) {
        FileOutputStream(path, true).use { out ->
            val process = ProcessBuilder("sh").fixPath(true).start()
            process.outputStream.bufferedWriter().use { commands ->
                commands.appendLine(
                    """
                    |echo dumpsys ${Context.WIFI_P2P_SERVICE}
                    |dumpsys ${Context.WIFI_P2P_SERVICE}
                    |echo
                    |echo dumpsys ${Context.CONNECTIVITY_SERVICE} tethering
                    |dumpsys ${Context.CONNECTIVITY_SERVICE} tethering
                    |echo
                    """.trimMargin()
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    val dumpCommand = if (Build.VERSION.SDK_INT >= 33) {
                        "dumpsys ${Context.CONNECTIVITY_SERVICE} trafficcontroller"
                    } else {
                        VpnFirewallManager.DUMP_COMMAND
                    }
                    commands.appendLine("echo $dumpCommand\n$dumpCommand\necho")
                    if (Build.VERSION.SDK_INT >= 31) {
                        commands.appendLine(
                            "settings get global ${VpnFirewallManager.UIDS_ALLOWED_ON_RESTRICTED_NETWORKS}"
                        )
                    }
                }
                commands.appendLine(
                    """
                    |echo iptables -t filter
                    |iptables-save -t filter
                    |echo
                    |echo iptables -t nat
                    |iptables-save -t nat
                    |echo
                    |echo ip6tables-save
                    |ip6tables-save
                    |echo
                    |echo ip rule
                    |$IP rule
                    |echo
                    |echo ip neigh
                    |$IP neigh
                    |echo
                    |echo iptables -nvx -L vpnhotspot_fwd
                    |$IPTABLES -nvx -L vpnhotspot_fwd
                    |echo
                    |echo iptables -nvx -L vpnhotspot_acl
                    |$IPTABLES -nvx -L vpnhotspot_acl
                    |echo
                    |echo logcat-su
                    |logcat -d
                    """.trimMargin()
                )
            }
            process.inputStream.copyTo(out)
            when (val exit = process.waitFor()) {
                0 -> Unit
                else -> out.write("Process exited with $exit".toByteArray())
            }
        }
        null
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(path)
        parcel.writeString(cacheDir.absolutePath)
    }

    companion object CREATOR : Parcelable.Creator<Dump> {
        override fun createFromParcel(parcel: Parcel) = Dump(parcel)
        override fun newArray(size: Int): Array<Dump?> = arrayOfNulls(size)
    }
}

sealed class ProcessData : Parcelable {
    class StdoutLine(val line: String) : ProcessData() {
        constructor(parcel: Parcel) : this(parcel.readString() ?: "")

        override fun describeContents() = 0
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(line)
        }

        companion object CREATOR : Parcelable.Creator<StdoutLine> {
            override fun createFromParcel(parcel: Parcel) = StdoutLine(parcel)
            override fun newArray(size: Int): Array<StdoutLine?> = arrayOfNulls(size)
        }
    }

    class StderrLine(val line: String) : ProcessData() {
        constructor(parcel: Parcel) : this(parcel.readString() ?: "")

        override fun describeContents() = 0
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(line)
        }

        companion object CREATOR : Parcelable.Creator<StderrLine> {
            override fun createFromParcel(parcel: Parcel) = StderrLine(parcel)
            override fun newArray(size: Int): Array<StderrLine?> = arrayOfNulls(size)
        }
    }

    class Exit(val code: Int) : ProcessData() {
        constructor(parcel: Parcel) : this(parcel.readInt())

        override fun describeContents() = 0
        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(code)
        }

        companion object CREATOR : Parcelable.Creator<Exit> {
            override fun createFromParcel(parcel: Parcel) = Exit(parcel)
            override fun newArray(size: Int): Array<Exit?> = arrayOfNulls(size)
        }
    }
}

class ProcessListener(
    private val terminateRegex: Regex,
    private vararg val command: String,
) : RootCommandChannel<ProcessData> {
    constructor(parcel: Parcel) : this(
        Regex(parcel.readString() ?: ""),
        *(parcel.createStringArray() ?: emptyArray()),
    )

    override fun create(scope: CoroutineScope) = scope.produce(Dispatchers.IO, capacity) {
        val process = ProcessBuilder(*command).fixPath().start()
        val parent = Job(coroutineContext.job)
        try {
            launch(parent) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            trySend(ProcessData.StdoutLine(line)).onClosed { return@useLines }
                                .onFailure { throw it!! }
                            if (terminateRegex.containsMatchIn(line)) {
                                process.destroy()
                            }
                        }
                    }
                } catch (_: InterruptedIOException) {
                } catch (e: IOException) {
                    if (!e.isEBADF) {
                        Timber.w(e)
                    }
                }
            }
            launch(parent) {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            trySend(ProcessData.StdoutLine(line)).onClosed { return@useLines }
                                .onFailure { throw it!! }
                        }
                    }
                } catch (_: InterruptedIOException) {
                } catch (e: IOException) {
                    if (!e.isEBADF) {
                        Timber.w(e)
                    }
                }
            }
            launch(parent) {
                trySend(ProcessData.Exit(process.waitFor())).onClosed { return@launch }
                    .onFailure { throw it!! }
            }
            parent.join()
        } finally {
            parent.cancel()
            if (process.isAlive) {
                process.destroyForcibly()
            }
            parent.join()
        }
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(terminateRegex.pattern)
        parcel.writeStringArray(command)
    }

    companion object CREATOR : Parcelable.Creator<ProcessListener> {
        override fun createFromParcel(parcel: Parcel) = ProcessListener(parcel)
        override fun newArray(size: Int): Array<ProcessListener?> = arrayOfNulls(size)
    }
}

class ReadArp() : RootCommand<ParcelableString> {
    constructor(@Suppress("UNUSED_PARAMETER") parcel: Parcel) : this()

    override suspend fun execute() = withContext(Dispatchers.IO) {
        ParcelableString(File("/proc/net/arp").readText())
    }

    override fun describeContents() = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

    companion object CREATOR : Parcelable.Creator<ReadArp> {
        override fun createFromParcel(parcel: Parcel) = ReadArp(parcel)
        override fun newArray(size: Int): Array<ReadArp?> = arrayOfNulls(size)
    }
}

@RequiresApi(30)
class StartTethering(
    private val type: Int,
    private val showProvisioningUi: Boolean,
) : RootCommand<ParcelableInt?> {
    constructor(parcel: Parcel) : this(parcel.readInt(), parcel.readInt() != 0)

    override suspend fun execute(): ParcelableInt? {
        val future = CompletableDeferred<Int?>()
        TetheringManagerCompat.startTethering(type, true, showProvisioningUi, {
            it.run()
        }, object : TetheringManager.StartTetheringCallback {
            override fun onTetheringStarted() {
                future.complete(null)
            }

            override fun onTetheringFailed(error: Int) {
                future.complete(error)
            }
        })
        return future.await()?.let(::ParcelableInt)
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(type)
        parcel.writeInt(if (showProvisioningUi) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<StartTethering> {
        override fun createFromParcel(parcel: Parcel) = StartTethering(parcel)
        override fun newArray(size: Int): Array<StartTethering?> = arrayOfNulls(size)
    }
}

@RequiresApi(30)
class StopTethering(
    private val cacheDir: File,
    private val type: Int,
) : RootCommand<ParcelableInt?> {
    constructor(parcel: Parcel) : this(
        File(parcel.readString() ?: app.deviceStorage.codeCacheDir.absolutePath),
        parcel.readInt(),
    )

    override suspend fun execute(): ParcelableInt? {
        val future = CompletableDeferred<Int?>()
        TetheringManagerCompat.stopTethering(
            type,
            object : TetheringManagerCompat.StopTetheringCallback {
                override fun onStopTetheringSucceeded() {
                    future.complete(null)
                }

                override fun onStopTetheringFailed(error: Int) {
                    future.complete(error)
                }

                override fun onException(e: Exception) {
                    future.completeExceptionally(e)
                }
            },
            Services.context,
            cacheDir,
        )
        return future.await()?.let(::ParcelableInt)
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(cacheDir.absolutePath)
        parcel.writeInt(type)
    }

    companion object CREATOR : Parcelable.Creator<StopTethering> {
        override fun createFromParcel(parcel: Parcel) = StopTethering(parcel)
        override fun newArray(size: Int): Array<StopTethering?> = arrayOfNulls(size)
    }
}

@Deprecated("Old API since API 30")
@Suppress("DEPRECATION")
class StartTetheringLegacy(
    private val cacheDir: File,
    private val type: Int,
    private val showProvisioningUi: Boolean,
) : RootCommand<ParcelableBoolean> {
    constructor(parcel: Parcel) : this(
        File(parcel.readString() ?: app.deviceStorage.codeCacheDir.absolutePath),
        parcel.readInt(),
        parcel.readInt() != 0,
    )

    override suspend fun execute(): ParcelableBoolean {
        val future = CompletableDeferred<Boolean>()
        val callback = object : TetheringManagerCompat.StartTetheringCallback {
            override fun onTetheringStarted() {
                future.complete(true)
            }

            override fun onTetheringFailed(error: Int?) {
                check(error == null)
                future.complete(false)
            }
        }
        TetheringManagerCompat.startTetheringLegacy(type, showProvisioningUi, callback, cacheDir = cacheDir)
        return ParcelableBoolean(future.await())
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(cacheDir.absolutePath)
        parcel.writeInt(type)
        parcel.writeInt(if (showProvisioningUi) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<StartTetheringLegacy> {
        override fun createFromParcel(parcel: Parcel) = StartTetheringLegacy(parcel)
        override fun newArray(size: Int): Array<StartTetheringLegacy?> = arrayOfNulls(size)
    }
}

class StopTetheringLegacy(private val type: Int) : RootCommandNoResult {
    constructor(parcel: Parcel) : this(parcel.readInt())

    override suspend fun execute(): Parcelable? {
        TetheringManagerCompat.stopTetheringLegacy(type)
        return null
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(type)
    }

    companion object CREATOR : Parcelable.Creator<StopTetheringLegacy> {
        override fun createFromParcel(parcel: Parcel) = StopTetheringLegacy(parcel)
        override fun newArray(size: Int): Array<StopTetheringLegacy?> = arrayOfNulls(size)
    }
}

class SettingsGlobalPut(
    private val name: String,
    private val value: String,
) : RootCommandNoResult {
    constructor(parcel: Parcel) : this(parcel.readString() ?: "", parcel.readString() ?: "")

    companion object CREATOR : Parcelable.Creator<SettingsGlobalPut> {
        suspend fun int(name: String, value: Int) {
            try {
                check(Settings.Global.putInt(Services.context.contentResolver, name, value))
            } catch (e: SecurityException) {
                try {
                    RootManager.use { it.execute(SettingsGlobalPut(name, value.toString())) }
                } catch (eRoot: Exception) {
                    eRoot.addSuppressed(e)
                    throw eRoot
                }
            }
        }

        override fun createFromParcel(parcel: Parcel) = SettingsGlobalPut(parcel)
        override fun newArray(size: Int): Array<SettingsGlobalPut?> = arrayOfNulls(size)
    }

    override suspend fun execute() = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("settings", "put", "global", name, value).fixPath(true).start()
        val error = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 || error.isNotEmpty()) {
            throw RemoteException("Process exited with $exit: $error")
        }
        null
    }

    override fun describeContents() = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(value)
    }
}
