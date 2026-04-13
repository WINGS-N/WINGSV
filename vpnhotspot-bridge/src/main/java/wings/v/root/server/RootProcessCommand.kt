package wings.v.root.server

import android.os.Parcel
import android.os.Parcelable
import be.mygod.librootkotlinx.RootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class RootProcessCommand(
    private val command: String,
    private val redirect: Boolean = false,
) : RootCommand<RootProcessResult> {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readInt() != 0,
    )

    override suspend fun execute(): RootProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("sh", "-c", command)
            .fixPathForRootServer(redirect)
            .start()
        coroutineScope {
            val output = async { process.inputStream.bufferedReader().use { it.readLimitedProcessOutput() } }
            val error = async {
                if (redirect) {
                    ""
                } else {
                    process.errorStream.bufferedReader().use { it.readLimitedProcessOutput() }
                }
            }
            RootProcessResult(process.waitFor(), output.await(), error.await())
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(command)
        parcel.writeInt(if (redirect) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<RootProcessCommand> {
        override fun createFromParcel(parcel: Parcel): RootProcessCommand = RootProcessCommand(parcel)
        override fun newArray(size: Int): Array<RootProcessCommand?> = arrayOfNulls(size)
    }
}

private fun ProcessBuilder.fixPathForRootServer(redirect: Boolean = false) = apply {
    environment().compute("PATH") { _, value ->
        if (value.isNullOrEmpty()) "/system/bin" else "$value:/system/bin"
    }
    redirectErrorStream(redirect)
}
