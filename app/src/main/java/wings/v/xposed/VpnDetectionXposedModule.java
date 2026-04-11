package wings.v.xposed;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import wings.v.core.XposedModulePrefs;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class VpnDetectionXposedModule implements IXposedHookLoadPackage {

    private static final String MODULE_PACKAGE = "wings.v";
    private static final String LOG_TAG = "WINGS-Xposed";
    private static final String FALLBACK_INTERFACE = "wlan0";
    private static final ThreadLocal<Boolean> CALLING_ORIGINAL = ThreadLocal.withInitial(() -> false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (
            loadPackageParam == null ||
            loadPackageParam.packageName == null ||
            MODULE_PACKAGE.equals(loadPackageParam.packageName) ||
            "android".equals(loadPackageParam.packageName)
        ) {
            return;
        }

        ModuleConfig config = ModuleConfig.load();
        if (!config.enabled || !config.shouldHook(loadPackageParam.packageName)) {
            return;
        }

        Log.i(
            LOG_TAG,
            "hooking " +
                loadPackageParam.packageName +
                " allApps=" +
                config.allApps +
                " native=" +
                config.nativeHookEnabled +
                " hideVpnApps=" +
                config.hideVpnApps
        );
        hookConnectivityApis();
        if (config.nativeHookEnabled && NativeVpnDetectionHook.install()) {
            hookNativeLibraryLoads();
            hookKnownNativeInterfaceDetectors(loadPackageParam.classLoader);
        }
        if (config.hideVpnApps) {
            hookPackageManager(loadPackageParam.classLoader, config.hiddenVpnPackages);
        }
    }

    private static void hookConnectivityApis() {
        hookGetActiveNetwork();
        hookGetAllNetworks();
        hookGetNetworkCapabilities();
        hookGetLinkProperties();
        hookNetworkCapabilities();
        hookLinkProperties();
        hookJavaNetworkInterfaces();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void hookNativeLibraryLoads() {
        XC_MethodHook refreshHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                NativeVpnDetectionHook.refresh();
            }
        };

        try {
            XposedBridge.hookAllMethods(Runtime.class, "loadLibrary0", refreshHook);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.hookAllMethods(Runtime.class, "load0", refreshHook);
        } catch (Throwable ignored) {}
    }

    private static void hookGetActiveNetwork() {
        XposedHelpers.findAndHookMethod(
            ConnectivityManager.class,
            "getActiveNetwork",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isCallingOriginal() || !(param.thisObject instanceof ConnectivityManager)) {
                        return;
                    }
                    ConnectivityManager connectivityManager = (ConnectivityManager) param.thisObject;
                    Network network = (Network) param.getResult();
                    if (!isVpnNetwork(connectivityManager, network)) {
                        return;
                    }
                    Network replacement = findPhysicalNetwork(connectivityManager);
                    if (replacement != null) {
                        param.setResult(replacement);
                    }
                }
            }
        );
    }

    private static void hookGetAllNetworks() {
        XposedHelpers.findAndHookMethod(
            ConnectivityManager.class,
            "getAllNetworks",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isCallingOriginal() || !(param.thisObject instanceof ConnectivityManager)) {
                        return;
                    }
                    Network[] networks = (Network[]) param.getResult();
                    if (networks == null || networks.length == 0) {
                        return;
                    }
                    ConnectivityManager connectivityManager = (ConnectivityManager) param.thisObject;
                    List<Network> filtered = new ArrayList<>(networks.length);
                    for (Network network : networks) {
                        if (!isVpnNetwork(connectivityManager, network)) {
                            filtered.add(network);
                        }
                    }
                    param.setResult(filtered.toArray(new Network[0]));
                }
            }
        );
    }

    private static void hookGetNetworkCapabilities() {
        XposedHelpers.findAndHookMethod(
            ConnectivityManager.class,
            "getNetworkCapabilities",
            Network.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isCallingOriginal()) {
                        return;
                    }
                    sanitizeNetworkCapabilities(param.getResult());
                }
            }
        );
    }

    private static void hookGetLinkProperties() {
        XposedHelpers.findAndHookMethod(
            ConnectivityManager.class,
            "getLinkProperties",
            Network.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isCallingOriginal() || !(param.thisObject instanceof ConnectivityManager)) {
                        return;
                    }
                    ConnectivityManager connectivityManager = (ConnectivityManager) param.thisObject;
                    Object result = param.getResult();
                    Network requestedNetwork =
                        param.args != null && param.args.length > 0 ? (Network) param.args[0] : null;
                    if (
                        isVpnNetwork(connectivityManager, requestedNetwork) ||
                        isTunnelInterface(getInterfaceName(result))
                    ) {
                        LinkProperties replacement = getPhysicalLinkProperties(connectivityManager);
                        if (replacement != null) {
                            param.setResult(replacement);
                        } else {
                            sanitizeLinkProperties(result);
                        }
                    }
                }
            }
        );
    }

    private static void hookNetworkCapabilities() {
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities.class,
            "hasTransport",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (
                        param.args != null &&
                        param.args.length == 1 &&
                        Integer.valueOf(NetworkCapabilities.TRANSPORT_VPN).equals(param.args[0]) &&
                        Boolean.TRUE.equals(param.getResult())
                    ) {
                        param.setResult(false);
                    }
                }
            }
        );

        XposedHelpers.findAndHookMethod(
            NetworkCapabilities.class,
            "getTransportInfo",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object transportInfo = param.getResult();
                    if (isVpnTransportInfo(transportInfo)) {
                        param.setResult(null);
                    }
                }
            }
        );
    }

    private static void hookLinkProperties() {
        XposedHelpers.findAndHookMethod(
            LinkProperties.class,
            "getInterfaceName",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String interfaceName = (String) param.getResult();
                    if (isTunnelInterface(interfaceName)) {
                        param.setResult(FALLBACK_INTERFACE);
                    }
                }
            }
        );

        try {
            XposedHelpers.findAndHookMethod(
                LinkProperties.class,
                "getAllInterfaceNames",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof List<?>)) {
                            return;
                        }
                        List<?> list = (List<?>) result;
                        List<String> filtered = new ArrayList<>(list.size());
                        for (Object value : list) {
                            if (value instanceof String && !isTunnelInterface((String) value)) {
                                filtered.add((String) value);
                            }
                        }
                        param.setResult(filtered);
                    }
                }
            );
        } catch (Throwable ignored) {}
    }

    private static void hookJavaNetworkInterfaces() {
        XposedHelpers.findAndHookMethod(
            NetworkInterface.class,
            "getNetworkInterfaces",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Enumeration<?>)) {
                        return;
                    }
                    Enumeration<?> enumeration = (Enumeration<?>) result;
                    List<NetworkInterface> filtered = new ArrayList<>();
                    while (enumeration.hasMoreElements()) {
                        Object next = enumeration.nextElement();
                        if (
                            next instanceof NetworkInterface && !isTunnelInterface(((NetworkInterface) next).getName())
                        ) {
                            filtered.add((NetworkInterface) next);
                        }
                    }
                    param.setResult(Collections.enumeration(filtered));
                }
            }
        );

        XposedHelpers.findAndHookMethod(
            NetworkInterface.class,
            "getByName",
            String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (
                        param.args != null &&
                        param.args.length == 1 &&
                        param.args[0] instanceof String &&
                        isTunnelInterface((String) param.args[0])
                    ) {
                        param.setResult(null);
                    }
                }
            }
        );
    }

    private static void hookKnownNativeInterfaceDetectors(ClassLoader classLoader) {
        Class<?> detectorClass = XposedHelpers.findClassIfExists(
            "com.cherepavel.vpndetector.detector.IfconfigTermuxLikeDetector",
            classLoader
        );
        if (detectorClass == null) {
            return;
        }
        XposedBridge.hookAllMethods(
            detectorClass,
            "getInterfacesNative",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String[])) {
                        return;
                    }
                    String[] blocks = (String[]) result;
                    List<String> filtered = new ArrayList<>(blocks.length);
                    for (String block : blocks) {
                        if (!isTunnelInterface(extractInterfaceName(block))) {
                            filtered.add(block);
                        }
                    }
                    param.setResult(filtered.toArray(new String[0]));
                }
            }
        );
    }

    private static void hookPackageManager(ClassLoader classLoader, Set<String> hiddenPackages) {
        if (hiddenPackages == null || hiddenPackages.isEmpty()) {
            return;
        }
        Class<?> packageManagerClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager",
            classLoader
        );
        if (packageManagerClass == null) {
            return;
        }

        XposedBridge.hookAllMethods(
            packageManagerClass,
            "getInstalledPackages",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(filterPackageInfoList(param.getResult(), hiddenPackages));
                }
            }
        );
        XposedBridge.hookAllMethods(
            packageManagerClass,
            "getInstalledApplications",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(filterApplicationInfoList(param.getResult(), hiddenPackages));
                }
            }
        );

        XC_MethodHook hideSinglePackageHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String packageName = extractPackageName(
                    param.args != null && param.args.length > 0 ? param.args[0] : null
                );
                if (packageName != null && hiddenPackages.contains(packageName)) {
                    param.setThrowable(new PackageManager.NameNotFoundException(packageName));
                }
            }
        };
        XposedBridge.hookAllMethods(packageManagerClass, "getPackageInfo", hideSinglePackageHook);
        XposedBridge.hookAllMethods(packageManagerClass, "getApplicationInfo", hideSinglePackageHook);
    }

    private static Object filterPackageInfoList(Object value, Set<String> hiddenPackages) {
        if (!(value instanceof List<?>)) {
            return value;
        }
        List<?> source = (List<?>) value;
        List<PackageInfo> filtered = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof PackageInfo && !hiddenPackages.contains(((PackageInfo) item).packageName)) {
                filtered.add((PackageInfo) item);
            }
        }
        return filtered;
    }

    private static Object filterApplicationInfoList(Object value, Set<String> hiddenPackages) {
        if (!(value instanceof List<?>)) {
            return value;
        }
        List<?> source = (List<?>) value;
        List<ApplicationInfo> filtered = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof ApplicationInfo && !hiddenPackages.contains(((ApplicationInfo) item).packageName)) {
                filtered.add((ApplicationInfo) item);
            }
        }
        return filtered;
    }

    private static String extractPackageName(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value == null) {
            return null;
        }
        try {
            Object packageName = value.getClass().getMethod("getPackageName").invoke(value);
            return packageName instanceof String ? (String) packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isVpnNetwork(ConnectivityManager connectivityManager, Network network) {
        if (connectivityManager == null || network == null) {
            return false;
        }
        NetworkCapabilities capabilities = getRawNetworkCapabilities(connectivityManager, network);
        if (capabilities != null) {
            try {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || hasVpnTransportInfo(capabilities)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        LinkProperties linkProperties = getRawLinkProperties(connectivityManager, network);
        return isTunnelInterface(getInterfaceName(linkProperties));
    }

    private static Network findPhysicalNetwork(ConnectivityManager connectivityManager) {
        if (connectivityManager == null) {
            return null;
        }
        Network[] networks = getRawAllNetworks(connectivityManager);
        if (networks == null) {
            return null;
        }
        for (Network network : networks) {
            NetworkCapabilities capabilities = getRawNetworkCapabilities(connectivityManager, network);
            if (capabilities == null) {
                continue;
            }
            try {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                if (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    return network;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static LinkProperties getPhysicalLinkProperties(ConnectivityManager connectivityManager) {
        Network replacement = findPhysicalNetwork(connectivityManager);
        return replacement != null ? getRawLinkProperties(connectivityManager, replacement) : null;
    }

    private static Network[] getRawAllNetworks(ConnectivityManager connectivityManager) {
        return callOriginal(() -> {
            Method method = ConnectivityManager.class.getMethod("getAllNetworks");
            return (Network[]) XposedBridge.invokeOriginalMethod(method, connectivityManager, new Object[0]);
        });
    }

    private static NetworkCapabilities getRawNetworkCapabilities(
        ConnectivityManager connectivityManager,
        Network network
    ) {
        return callOriginal(() -> {
            Method method = ConnectivityManager.class.getMethod("getNetworkCapabilities", Network.class);
            return (NetworkCapabilities) XposedBridge.invokeOriginalMethod(
                method,
                connectivityManager,
                new Object[] { network }
            );
        });
    }

    private static LinkProperties getRawLinkProperties(ConnectivityManager connectivityManager, Network network) {
        return callOriginal(() -> {
            Method method = ConnectivityManager.class.getMethod("getLinkProperties", Network.class);
            return (LinkProperties) XposedBridge.invokeOriginalMethod(
                method,
                connectivityManager,
                new Object[] { network }
            );
        });
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void sanitizeNetworkCapabilities(Object value) {
        if (!(value instanceof NetworkCapabilities)) {
            return;
        }
        NetworkCapabilities capabilities = (NetworkCapabilities) value;
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                invokeNetworkCapabilitiesMutator(
                    capabilities,
                    "removeTransportType",
                    NetworkCapabilities.TRANSPORT_VPN
                );
                invokeNetworkCapabilitiesMutator(capabilities, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI);
                invokeNetworkCapabilitiesMutator(
                    capabilities,
                    "addCapability",
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN
                );
            }
        } catch (Throwable ignored) {}
        try {
            if (hasVpnTransportInfo(capabilities)) {
                Method setTransportInfo = NetworkCapabilities.class.getMethod(
                    "setTransportInfo",
                    Class.forName("android.net.TransportInfo")
                );
                setTransportInfo.invoke(capabilities, (Object) null);
            }
        } catch (Throwable ignored) {}
    }

    private static void invokeNetworkCapabilitiesMutator(
        NetworkCapabilities capabilities,
        String methodName,
        int value
    ) {
        try {
            Method method = NetworkCapabilities.class.getMethod(methodName, int.class);
            method.invoke(capabilities, value);
        } catch (Throwable ignored) {}
    }

    private static boolean hasVpnTransportInfo(NetworkCapabilities capabilities) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isVpnTransportInfo(capabilities.getTransportInfo());
    }

    private static void sanitizeLinkProperties(Object value) {
        if (!(value instanceof LinkProperties)) {
            return;
        }
        try {
            Method setInterfaceName = LinkProperties.class.getMethod("setInterfaceName", String.class);
            setInterfaceName.invoke(value, FALLBACK_INTERFACE);
        } catch (Throwable ignored) {}
    }

    private static String getInterfaceName(Object value) {
        if (!(value instanceof LinkProperties)) {
            return null;
        }
        try {
            return ((LinkProperties) value).getInterfaceName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTunnelInterface(String interfaceName) {
        if (interfaceName == null) {
            return false;
        }
        String normalized = interfaceName.toLowerCase(Locale.ROOT);
        return (
            normalized.startsWith("tun") ||
            normalized.startsWith("tap") ||
            normalized.startsWith("ppp") ||
            normalized.startsWith("wg") ||
            normalized.contains("wireguard")
        );
    }

    private static String extractInterfaceName(String block) {
        if (block == null) {
            return null;
        }
        int lineEnd = block.indexOf('\n');
        String firstLine = lineEnd >= 0 ? block.substring(0, lineEnd) : block;
        int colon = firstLine.indexOf(':');
        return (colon >= 0 ? firstLine.substring(0, colon) : firstLine).trim();
    }

    private static boolean isVpnTransportInfo(Object value) {
        return value != null && value.getClass().getName().contains("VpnTransportInfo");
    }

    private static boolean isCallingOriginal() {
        Boolean callingOriginal = CALLING_ORIGINAL.get();
        return callingOriginal != null && callingOriginal;
    }

    private static <T> T callOriginal(OriginalCall<T> call) {
        boolean previous = isCallingOriginal();
        CALLING_ORIGINAL.set(true);
        try {
            return call.call();
        } catch (Throwable ignored) {
            return null;
        } finally {
            CALLING_ORIGINAL.set(previous);
        }
    }

    @FunctionalInterface
    private interface OriginalCall<T> {
        T call() throws Throwable;
    }

    private static final class ModuleConfig {

        final boolean enabled;
        final boolean allApps;
        final boolean nativeHookEnabled;
        final boolean hideVpnApps;
        final Set<String> targetPackages;
        final Set<String> hiddenVpnPackages;

        private ModuleConfig(
            boolean enabled,
            boolean allApps,
            boolean nativeHookEnabled,
            boolean hideVpnApps,
            Set<String> targetPackages,
            Set<String> hiddenVpnPackages
        ) {
            this.enabled = enabled;
            this.allApps = allApps;
            this.nativeHookEnabled = nativeHookEnabled;
            this.hideVpnApps = hideVpnApps;
            this.targetPackages = targetPackages;
            this.hiddenVpnPackages = hiddenVpnPackages;
        }

        static ModuleConfig load() {
            XSharedPreferences preferences = new XSharedPreferences(MODULE_PACKAGE, XposedModulePrefs.PREFS_NAME);
            preferences.makeWorldReadable();
            preferences.reload();
            return new ModuleConfig(
                preferences.getBoolean(XposedModulePrefs.KEY_ENABLED, XposedModulePrefs.DEFAULT_ENABLED),
                preferences.getBoolean(XposedModulePrefs.KEY_ALL_APPS, XposedModulePrefs.DEFAULT_ALL_APPS),
                getSystemBoolean(
                    XposedModulePrefs.PROP_NATIVE_HOOK_ENABLED,
                    preferences.getBoolean(
                        XposedModulePrefs.KEY_NATIVE_HOOK_ENABLED,
                        XposedModulePrefs.DEFAULT_NATIVE_HOOK_ENABLED
                    )
                ),
                preferences.getBoolean(XposedModulePrefs.KEY_HIDE_VPN_APPS, XposedModulePrefs.DEFAULT_HIDE_VPN_APPS),
                getPackages(preferences, XposedModulePrefs.KEY_TARGET_PACKAGES, ""),
                getPackages(
                    preferences,
                    XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES,
                    XposedModulePrefs.DEFAULT_HIDDEN_VPN_PACKAGES
                )
            );
        }

        boolean shouldHook(String packageName) {
            return allApps || targetPackages.contains(packageName);
        }

        private static boolean getSystemBoolean(String key, boolean fallback) {
            try {
                Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
                Method getMethod = systemPropertiesClass.getMethod("get", String.class);
                Object value = getMethod.invoke(null, key);
                if (!(value instanceof String)) {
                    return fallback;
                }
                String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    return fallback;
                }
                return (
                    "1".equals(normalized) ||
                    "true".equals(normalized) ||
                    "y".equals(normalized) ||
                    "yes".equals(normalized) ||
                    "on".equals(normalized)
                );
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private static Set<String> getPackages(XSharedPreferences preferences, String key, String defaultValue) {
            try {
                Set<String> stored = preferences.getStringSet(key, null);
                if (stored != null) {
                    return new LinkedHashSet<>(stored);
                }
            } catch (Throwable ignored) {}
            try {
                return XposedModulePrefs.parsePackageSet(preferences.getString(key, defaultValue));
            } catch (Throwable ignored) {
                return XposedModulePrefs.parsePackageSet(defaultValue);
            }
        }
    }
}
