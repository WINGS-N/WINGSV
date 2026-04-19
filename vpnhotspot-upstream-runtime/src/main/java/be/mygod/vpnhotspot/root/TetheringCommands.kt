package be.mygod.vpnhotspot.root

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch

object TetheringCommands {
    class OnClientsChanged(val clients: List<Parcelable>) : Parcelable {
        constructor(parcel: Parcel) : this(
            buildList {
                parcel.readList(this, Parcelable::class.java.classLoader, Parcelable::class.java)
            }
        )

        fun dispatch(callback: TetheringManagerCompat.TetheringEventCallback) = callback.onClientsChanged(clients)

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeList(clients)
        }

        companion object CREATOR : Parcelable.Creator<OnClientsChanged> {
            override fun createFromParcel(parcel: Parcel) = OnClientsChanged(parcel)
            override fun newArray(size: Int): Array<OnClientsChanged?> = arrayOfNulls(size)
        }
    }

    @RequiresApi(30)
    class RegisterTetheringEventCallback() : RootCommandChannel<OnClientsChanged> {
        constructor(@Suppress("UNUSED_PARAMETER") parcel: Parcel) : this()

        override fun create(scope: CoroutineScope) = scope.produce(capacity = capacity) {
            val finish = CompletableDeferred<Unit>()
            val callback = object : TetheringManagerCompat.TetheringEventCallback {
                private fun push(parcel: OnClientsChanged) {
                    trySend(parcel).onClosed {
                        finish.completeExceptionally(it ?: ClosedSendChannelException("Channel was closed normally"))
                        return
                    }.onFailure { throw it!! }
                }

                override fun onClientsChanged(clients: Collection<Parcelable>) =
                    push(OnClientsChanged(clients.toList()))
            }
            TetheringManagerCompat.registerTetheringEventCallback(callback) {
                scope.launch {
                    try {
                        it.run()
                    } catch (e: Throwable) {
                        finish.completeExceptionally(e)
                    }
                }
            }
            try {
                finish.await()
            } finally {
                TetheringManagerCompat.unregisterTetheringEventCallback(callback)
            }
        }

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

        companion object CREATOR : Parcelable.Creator<RegisterTetheringEventCallback> {
            override fun createFromParcel(parcel: Parcel) = RegisterTetheringEventCallback(parcel)
            override fun newArray(size: Int): Array<RegisterTetheringEventCallback?> = arrayOfNulls(size)
        }
    }
}
