package wings.v.root.server

import android.os.Parcel
import android.os.Parcelable
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootCommandOneWay
import be.mygod.librootkotlinx.systemContext

class RootInitCommand() : RootCommandOneWay {
    override suspend fun execute() {
        Logger.me = WingsRootManager
        RootServices.init { systemContext }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

    companion object CREATOR : Parcelable.Creator<RootInitCommand> {
        override fun createFromParcel(parcel: Parcel): RootInitCommand = RootInitCommand()
        override fun newArray(size: Int): Array<RootInitCommand?> = arrayOfNulls(size)
    }
}
