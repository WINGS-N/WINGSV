package be.mygod.vpnhotspot.root

import android.annotation.SuppressLint
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import be.mygod.librootkotlinx.AppProcess
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.ParcelableThrowable
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession
import be.mygod.librootkotlinx.systemContext
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UnblockCentral
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch
import timber.log.Timber

object RootManager : RootSession(), Logger {
    private var logThrowable = { priority: Int, t: Throwable ->
        if (priority >= Log.WARN) {
            t.printStackTrace(System.err)
        }
    }

    class RootInit() : RootCommandNoResult {
        constructor(@Suppress("UNUSED_PARAMETER") parcel: Parcel) : this()

        override suspend fun execute() = null.also {
            Timber.plant(object : Timber.DebugTree() {
                @SuppressLint("LogNotTimber")
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= Log.WARN) {
                        System.err.println("$priority/$tag: $message")
                        t?.printStackTrace()
                    }
                    if (t == null) {
                        Log.println(priority, tag, message)
                    } else {
                        Log.println(priority, tag, message)
                        Log.d(tag, message, t)
                        logThrowable(priority, t)
                    }
                }
            })
            Logger.me = RootManager
            Services.init { systemContext }
            UnblockCentral.needInit = false
        }

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

        companion object CREATOR : Parcelable.Creator<RootInit> {
            override fun createFromParcel(parcel: Parcel) = RootInit(parcel)
            override fun newArray(size: Int): Array<RootInit?> = arrayOfNulls(size)
        }
    }

    data class LoggedThrowable(val priority: Int, val t: ParcelableThrowable) : Parcelable {
        constructor(parcel: Parcel) : this(
            parcel.readInt(),
            checkNotNull(parcel.readParcelable(ParcelableThrowable::class.java.classLoader))
        )

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeInt(priority)
            parcel.writeParcelable(t, flags)
        }

        companion object CREATOR : Parcelable.Creator<LoggedThrowable> {
            override fun createFromParcel(parcel: Parcel) = LoggedThrowable(parcel)
            override fun newArray(size: Int): Array<LoggedThrowable?> = arrayOfNulls(size)
        }
    }

    class RootInitLogThrowable() : RootCommandChannel<LoggedThrowable> {
        constructor(@Suppress("UNUSED_PARAMETER") parcel: Parcel) : this()

        override fun create(scope: CoroutineScope): ReceiveChannel<LoggedThrowable> {
            val channel = Channel<Pair<Int, Throwable>>(Channel.CONFLATED) { (priority, t) ->
                if (priority >= Log.WARN) {
                    t.printStackTrace(System.err)
                }
            }
            return scope.produce {
                channel.consumeEach { (priority, t) ->
                    send(LoggedThrowable(priority, ParcelableThrowable(t)))
                }
            }.also {
                logThrowable = { priority, t ->
                    channel.trySend(priority to t).exceptionOrNull()?.printStackTrace(System.err)
                }
            }
        }

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

        companion object CREATOR : Parcelable.Creator<RootInitLogThrowable> {
            override fun createFromParcel(parcel: Parcel) = RootInitLogThrowable(parcel)
            override fun newArray(size: Int): Array<RootInitLogThrowable?> = arrayOfNulls(size)
        }
    }

    override fun d(m: String?, t: Throwable?) = Timber.d(t, m)
    override fun e(m: String?, t: Throwable?) = Timber.e(t, m)
    override fun i(m: String?, t: Throwable?) = Timber.i(t, m)
    override fun w(m: String?, t: Throwable?) = Timber.w(t, m)

    override suspend fun initServer(server: RootServer) {
        Logger.me = this
        AppProcess.shouldRelocateHeuristics.let {
            FirebaseCrashlytics.getInstance().setCustomKey("RootManager.relocateEnabled", it)
            server.init(app, it)
        }
        server.execute(RootInit())
        val throwables = server.create(RootInitLogThrowable(), GlobalScope)
        GlobalScope.launch {
            try {
                throwables.consumeEach { (priority, p) ->
                    Timber.tag("RootRemote").log(priority, p.unwrap())
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }
}
