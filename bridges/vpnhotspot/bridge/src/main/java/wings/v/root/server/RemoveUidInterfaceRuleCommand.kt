package wings.v.root.server

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException

@RequiresApi(29)
class RemoveUidInterfaceRuleCommand(
    private val uid: Int,
) : RootCommand<ParcelableBoolean> {
    constructor(parcel: Parcel) : this(parcel.readInt())

    companion object CREATOR : Parcelable.Creator<RemoveUidInterfaceRuleCommand> {
        private fun findConnectivityClass(baseName: String, loader: ClassLoader? = RemoveUidInterfaceRuleCommand::class.java.classLoader): Class<*> {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    return Class.forName("android.net.connectivity.$baseName", true, loader)
                } catch (_: ClassNotFoundException) {
                }
                try {
                    return Class.forName("com.android.connectivity.$baseName", true, loader)
                } catch (_: ClassNotFoundException) {
                }
            }
            return Class.forName(baseName, true, loader)
        }

        override fun createFromParcel(parcel: Parcel): RemoveUidInterfaceRuleCommand =
            RemoveUidInterfaceRuleCommand(parcel)

        override fun newArray(size: Int): Array<RemoveUidInterfaceRuleCommand?> = arrayOfNulls(size)
    }

    private object Impl29 {
        private val stub by lazy {
            findConnectivityClass("android.net.INetd\$Stub", if (Build.VERSION.SDK_INT >= 30) {
                PathClassLoader(
                    "/apex/com.android.tethering/javalib/service-connectivity.jar${File.pathSeparator}/system/framework/services.jar",
                    javaClass.classLoader
                )
            } else PathClassLoader("/system/framework/services.jar", javaClass.classLoader))
        }
        val netd by lazy {
            stub.getDeclaredMethod("asInterface", IBinder::class.java)(null, RootServices.netd)
        }
        private val firewallRemoveUidInterfaceRules by lazy {
            stub.getMethod("firewallRemoveUidInterfaceRules", IntArray::class.java)
        }

        operator fun invoke(uid: Int) {
            try {
                firewallRemoveUidInterfaceRules(netd, intArrayOf(uid))
            } catch (e: InvocationTargetException) {
                if (e.cause?.message != "[Operation not supported on transport endpoint] : eBPF not supported") {
                    throw e
                }
            }
        }
    }

    @RequiresApi(33)
    private object JniBpfMap {
        private val constants by lazy { findConnectivityClass("android.net.BpfNetMapsConstants") }
        private val mapPath by lazy {
            try {
                constants.getDeclaredField("UID_OWNER_MAP_PATH").get(null) as String?
            } catch (_: ReflectiveOperationException) {
                "/sys/fs/bpf/netd_shared/map_netd_uid_owner_map"
            }
        }
        private val matches by lazy {
            try {
                constants.getDeclaredField("IIF_MATCH").getLong(null) or
                        constants.getDeclaredField("LOCKDOWN_VPN_MATCH").getLong(null)
            } catch (_: ReflectiveOperationException) {
                3L shl 7
            }
        }

        operator fun invoke(uid: Int) = WingsJni.removeUidInterfaceRules(mapPath, uid, matches)
    }

    override suspend fun execute(): ParcelableBoolean = ParcelableBoolean(
        if (Build.VERSION.SDK_INT < 33) {
            Impl29(uid)
            true
        } else {
            JniBpfMap(uid)
        }
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(uid)
    }
}
