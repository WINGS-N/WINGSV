package be.mygod.vpnhotspot.root

import android.os.Parcel
import android.os.Parcelable
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.vpnhotspot.net.Routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

object RoutingCommands {
    class Clean() : RootCommandNoResult {
        constructor(@Suppress("UNUSED_PARAMETER") parcel: Parcel) : this()

        override suspend fun execute() = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("sh").fixPath(true).start()
            process.outputStream.bufferedWriter().use(Routing.Companion::appendCleanCommands)
            when (val code = process.waitFor()) {
                0 -> Unit
                else -> Timber.w("Unexpected exit code $code")
            }
            check(process.waitFor() == 0)
            null
        }

        override fun describeContents() = 0
        override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

        companion object CREATOR : Parcelable.Creator<Clean> {
            override fun createFromParcel(parcel: Parcel) = Clean(parcel)
            override fun newArray(size: Int): Array<Clean?> = arrayOfNulls(size)
        }
    }

    class UnexpectedOutputException(msg: String, val result: ProcessResult) : RuntimeException(msg)

    data class ProcessResult(val exit: Int, val out: String, val err: String) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readInt(),
            parcel.readString() ?: "",
            parcel.readString() ?: "",
        )

        fun message(
            command: List<String>,
            out: Boolean = this.out.isNotEmpty(),
            err: Boolean = this.err.isNotEmpty(),
        ): String? {
            val msg = StringBuilder("${command.joinToString(" ")} exited with $exit")
            if (out) {
                msg.append("\n${this.out}")
            }
            if (err) {
                msg.append("\n=== stderr ===\n${this.err}")
            }
            return if (exit != 0 || out || err) msg.toString() else null
        }

        fun check(
            command: List<String>,
            out: Boolean = this.out.isNotEmpty(),
            err: Boolean = this.err.isNotEmpty(),
        ) = message(command, out, err)?.let { msg ->
            throw UnexpectedOutputException(msg, this)
        }

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(exit)
            parcel.writeString(out)
            parcel.writeString(err)
        }

        companion object CREATOR : Parcelable.Creator<ProcessResult> {
            override fun createFromParcel(parcel: Parcel) = ProcessResult(parcel)
            override fun newArray(size: Int): Array<ProcessResult?> = arrayOfNulls(size)
        }
    }

    class Process(
        val command: List<String>,
        private val redirect: Boolean = false,
    ) : RootCommand<ProcessResult> {
        constructor(parcel: Parcel) : this(
            buildList {
                val size = parcel.readInt()
                repeat(size) {
                    add(parcel.readString() ?: "")
                }
            },
            parcel.readInt() != 0,
        )

        override suspend fun execute() = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).fixPath(redirect).start()
            coroutineScope {
                val output = async { process.inputStream.bufferedReader().readText() }
                val error = async { if (redirect) "" else process.errorStream.bufferedReader().readText() }
                ProcessResult(process.waitFor(), output.await(), error.await())
            }
        }

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(command.size)
            command.forEach(parcel::writeString)
            parcel.writeInt(if (redirect) 1 else 0)
        }

        companion object CREATOR : Parcelable.Creator<Process> {
            override fun createFromParcel(parcel: Parcel) = Process(parcel)
            override fun newArray(size: Int): Array<Process?> = arrayOfNulls(size)
        }
    }
}
