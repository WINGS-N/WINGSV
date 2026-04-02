package wings.v.root.server

import android.os.Parcel
import android.os.Parcelable
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class RootShellCommand(
    private val command: String,
    private val redirect: Boolean = false,
) : RootCommand<ParcelableString> {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readInt() != 0,
    )

    override suspend fun execute(): ParcelableString = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("sh", "-c", command)
            .fixPath(redirect)
            .start()
        coroutineScope {
            val output = async { process.inputStream.bufferedReader().use { it.readText() } }
            val error = async {
                if (redirect) {
                    ""
                } else {
                    process.errorStream.bufferedReader().use { it.readText() }
                }
            }
            val exitCode = process.waitFor()
            val stdout = output.await()
            val stderr = error.await()
            if (exitCode != 0) {
                val message = when {
                    stdout.isNotBlank() -> stdout.trim()
                    stderr.isNotBlank() -> stderr.trim()
                    else -> "Root command exited with code $exitCode"
                }
                throw IllegalStateException(message)
            }
            ParcelableString(stdout)
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(command)
        parcel.writeInt(if (redirect) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<RootShellCommand> {
        override fun createFromParcel(parcel: Parcel): RootShellCommand = RootShellCommand(parcel)
        override fun newArray(size: Int): Array<RootShellCommand?> = arrayOfNulls(size)
    }
}

private fun ProcessBuilder.fixPath(redirect: Boolean = false) = apply {
    environment().compute("PATH") { _, value ->
        if (value.isNullOrEmpty()) "/system/bin" else "$value:/system/bin"
    }
    redirectErrorStream(redirect)
}
