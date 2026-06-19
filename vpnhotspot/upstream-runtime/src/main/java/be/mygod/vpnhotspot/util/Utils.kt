package be.mygod.vpnhotspot.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.RemoteException
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetAddress
import android.content.res.Resources
import java.util.concurrent.Executor
import timber.log.Timber

tailrec fun Throwable.getRootCause(): Throwable {
    if (this is InvocationTargetException || this is RemoteException) return (cause ?: return this).getRootCause()
    return this
}

val Throwable.readableMessage: String
    get() = getRootCause().localizedMessage ?: getRootCause().javaClass.name

fun Method.matches(name: String, vararg classes: Class<*>) = this.name == name &&
    parameterCount == classes.size &&
    classes.indices.all { parameters[it].type == classes[it] }

inline fun <reified T> Method.matches1(name: String) = matches(name, T::class.java)

fun Context.ensureReceiverUnregistered(receiver: BroadcastReceiver) {
    try {
        unregisterReceiver(receiver)
    } catch (_: IllegalArgumentException) {
    }
}

fun broadcastReceiver(receiver: (Context, Intent) -> Unit) = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = receiver(context, intent)
}

fun intentFilter(vararg actions: String) = IntentFilter().also { actions.forEach(it::addAction) }

fun parseNumericAddress(address: String): InetAddress = if (Build.VERSION.SDK_INT >= 29) {
    InetAddresses.parseNumericAddress(address)
} else {
    val parseNumericAddress = InetAddress::class.java.getDeclaredMethod("parseNumericAddress", String::class.java)
    parseNumericAddress(null, address) as InetAddress
}

private val getAllInterfaceNames by lazy { LinkProperties::class.java.getDeclaredMethod("getAllInterfaceNames") }
@Suppress("UNCHECKED_CAST")
val LinkProperties.allInterfaceNames: List<String>
    get() = getAllInterfaceNames(this) as List<String>

fun Resources.findIdentifier(name: String, defType: String, defPackage: String, alternativePackage: String? = null) =
    getIdentifier(name, defType, defPackage).let {
        if (alternativePackage != null && it == 0) getIdentifier(name, defType, alternativePackage) else it
    }

private val newLookup by lazy {
    MethodHandles.Lookup::class.java.getDeclaredConstructor(Class::class.java, Int::class.java).apply {
        isAccessible = true
    }
}

fun Class<*>.privateLookup() = if (Build.VERSION.SDK_INT < 33) try {
    newLookup.newInstance(this, 0xf)
} catch (_: ReflectiveOperationException) {
    MethodHandles.lookup().`in`(this)
} else MethodHandles.privateLookupIn(this, MethodHandles.lookup())

fun InvocationHandler.callSuper(interfaceClass: Class<*>, proxy: Any, method: Method, args: Array<out Any?>?) =
    when {
        method.isDefault -> interfaceClass.privateLookup().unreflectSpecial(method, interfaceClass).bindTo(proxy).run {
            invokeWithArguments(*(args ?: emptyArray()))
        }
        method.declaringClass.isAssignableFrom(javaClass) -> when {
            method.declaringClass == Object::class.java -> when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "${proxy.javaClass.name}@${System.identityHashCode(proxy).toString(16)}"
                else -> error("Unsupported Object method dispatched")
            }
            args == null -> method(this)
            else -> method(this, *args)
        }
        else -> {
            Timber.w("Unhandled method: $method(${args?.contentDeepToString()})")
            null
        }
    }

fun globalNetworkRequestBuilder() = NetworkRequest.Builder().apply {
    if (Build.VERSION.SDK_INT >= 31) {
        setIncludeOtherUidNetworks(true)
    }
}

object InPlaceExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}
