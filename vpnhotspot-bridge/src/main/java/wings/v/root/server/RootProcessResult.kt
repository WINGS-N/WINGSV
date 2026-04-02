package wings.v.root.server

import android.os.Parcel
import android.os.Parcelable

class RootProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
    )

    fun primaryMessage(): String {
        return when {
            stdout.isNotBlank() -> stdout.trim()
            stderr.isNotBlank() -> stderr.trim()
            else -> "Root command exited with code $exitCode"
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
    }

    companion object CREATOR : Parcelable.Creator<RootProcessResult> {
        override fun createFromParcel(parcel: Parcel): RootProcessResult = RootProcessResult(parcel)
        override fun newArray(size: Int): Array<RootProcessResult?> = arrayOfNulls(size)
    }
}
