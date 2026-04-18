package wings.v.xposed;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import wings.v.core.XposedAttackVector;
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
    private static final String VPN_SERVICE_PERMISSION = "android.permission.BIND_VPN_SERVICE";
    private static final String[] CRITICAL_INFRASTRUCTURE_PACKAGES = new String[] {
        "system",
        "com.android.systemui",
        "com.android.phone",
        "com.android.networkstack.process",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.sec.android.app.packageinstaller",
        "com.android.webview",
        "com.google.android.webview",
        "com.google.android.trichromelibrary",
        "com.google.android.setupwizard",
        "com.samsung.android.setupwizard",
        "com.sec.android.app.SecSetupWizard",
        "com.android.managedprovisioning",
        "com.android.vending",
        "com.android.networkstack",
        "com.google.android.networkstack.process",
        "com.google.android.networkstack",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.process.gservices",
        "com.sec.imsservice",
        "com.sec.epdg",
        "com.samsung.android.mcfds",
    };
    private static final String[] CRITICAL_INFRASTRUCTURE_PREFIXES = new String[] {
        "com.android.providers.",
        "com.google.android.providers.",
        "com.qualcomm.qti.",
        "com.qualcomm.qcril",
        "org.codeaurora.ims",
        "com.mediatek.",
        "com.sec.ims",
        "com.sec.epdg",
        "com.sec.phone",
        "com.samsung.android.telephony",
        "com.samsung.android.network",
    };
    private static final String[] CRITICAL_INFRASTRUCTURE_PROCESS_KEYWORDS = new String[] {
        "ril",
        "qcril",
        "radio",
        "telephony",
        "ims",
        "epdg",
    };
    private static final String[] WEBVIEW_STACK_PREFIXES = new String[] {
        "org.chromium.",
        "com.android.webview.chromium.",
        "android.webkit.",
        "androidx.webkit.",
    };
    private static final String[] FRAMEWORK_NETWORK_INTERNAL_STACK_PREFIXES = new String[] {
        "android.net.LinkProperties$",
        "android.net.ConnectivityManager$",
        "android.os.Parcel",
        "android.os.BaseBundle",
    };
    private static final ThreadLocal<Boolean> CALLING_ORIGINAL = ThreadLocal.withInitial(() -> false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null || loadPackageParam.packageName == null) {
            return;
        }

        final String packageName = loadPackageParam.packageName;

        if (MODULE_PACKAGE.equals(packageName)) {
            return;
        }

        final ModuleConfig config = ModuleConfig.load();
        if (!config.enabled) {
            return;
        }

        if ("android".equals(packageName)) {
            if (!isSystemServerProcess(loadPackageParam)) {
                Log.i(
                    LOG_TAG,
                    "Skipping framework hooks outside system_server: package=" +
                        packageName +
                        ", process=" +
                        loadPackageParam.processName
                );
                return;
            }
            Log.i(LOG_TAG, "Hooking system_server for network services and dumpsys...");
            hookSystemServices(loadPackageParam.classLoader, config);
            return;
        }

        if (isCriticalInfrastructureTarget(loadPackageParam)) {
            Log.i(LOG_TAG, "Skipping in-process hooks for critical system package: " + packageName);
            return;
        }
        if (isProtectedSystemTarget(loadPackageParam) && !config.targetPackages.contains(packageName)) {
            Log.i(LOG_TAG, "Skipping in-process hooks for system app: " + packageName);
            return;
        }
        if (
            config.allApps && isPreinstalledSystemApp(loadPackageParam) && !config.targetPackages.contains(packageName)
        ) {
            Log.i(
                LOG_TAG,
                "Skipping in-process hooks for preinstalled system app outside explicit targets: " + packageName
            );
            return;
        }

        boolean shouldApplyHooks;
        if (config.allApps) {
            shouldApplyHooks = true;
        } else {
            shouldApplyHooks = config.targetPackages.contains(packageName);
        }

        if (!shouldApplyHooks) {
            return;
        }

        Log.i(LOG_TAG, "Applying in-process hooks to " + packageName + ", allApps=" + config.allApps);
        hookInProcessApis(loadPackageParam.classLoader, config);
    }

    private static boolean isSystemServerProcess(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null) {
            return false;
        }
        final String processName = loadPackageParam.processName;
        if (!"android".equals(processName) && !"system_server".equals(processName)) {
            return false;
        }
        return loadPackageParam.appInfo != null && loadPackageParam.appInfo.uid == Process.SYSTEM_UID;
    }

    private static boolean isProtectedSystemTarget(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null || loadPackageParam.appInfo == null) {
            return false;
        }
        final int flags = loadPackageParam.appInfo.flags;
        return (
            (flags & ApplicationInfo.FLAG_PERSISTENT) != 0 ||
            loadPackageParam.appInfo.uid < Process.FIRST_APPLICATION_UID
        );
    }

    private static boolean isPreinstalledSystemApp(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null || loadPackageParam.appInfo == null) {
            return false;
        }
        final int flags = loadPackageParam.appInfo.flags;
        return ((flags & ApplicationInfo.FLAG_SYSTEM) != 0 || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
    }

    private static boolean isCriticalInfrastructureTarget(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null) {
            return false;
        }
        final String packageName = loadPackageParam.packageName;
        final String processName = loadPackageParam.processName;
        for (String candidate : CRITICAL_INFRASTRUCTURE_PACKAGES) {
            if (matchesCriticalName(packageName, candidate) || matchesCriticalName(processName, candidate)) {
                return true;
            }
        }
        for (String candidate : CRITICAL_INFRASTRUCTURE_PREFIXES) {
            if (
                (packageName != null && packageName.startsWith(candidate)) ||
                (processName != null && processName.startsWith(candidate))
            ) {
                return true;
            }
        }
        if (processName == null) {
            return false;
        }
        final String normalizedProcessName = processName.toLowerCase(Locale.ROOT);
        for (String keyword : CRITICAL_INFRASTRUCTURE_PROCESS_KEYWORDS) {
            if (normalizedProcessName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCriticalName(String value, String candidate) {
        if (value == null || candidate == null) {
            return false;
        }
        return (value.equals(candidate) || value.startsWith(candidate + ":") || value.startsWith(candidate + "."));
    }

    private static String resolveSpoofCallerPackage(final ModuleConfig config) {
        if (config == null || config.targetPackages == null || config.targetPackages.isEmpty()) {
            return null;
        }
        final int callingUid;
        final int callingPid;
        try {
            callingUid = Binder.getCallingUid();
            callingPid = Binder.getCallingPid();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (callingUid < Process.FIRST_APPLICATION_UID) {
            return null;
        }
        if (callingPid <= 0 || callingPid == Process.myPid()) {
            return null;
        }
        try {
            final String[] packages = getPackagesForUid(callingUid);
            if (packages == null || packages.length == 0) {
                return null;
            }
            for (final String packageName : packages) {
                if (packageName == null) {
                    continue;
                }
                if (MODULE_PACKAGE.equals(packageName)) {
                    continue;
                }
                if (config.targetPackages.contains(packageName)) {
                    Log.d(LOG_TAG, "Spoofing for UID " + callingUid + " (" + packageName + ")");
                    return packageName;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static void hookSystemServices(final ClassLoader classLoader, final ModuleConfig config) {
        try {
            final Class<?> connectivityServiceClass = XposedHelpers.findClass(
                "com.android.server.ConnectivityService",
                classLoader
            );

            XposedBridge.hookAllMethods(
                connectivityServiceClass,
                "getActiveNetworkForUid",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        resolveSpoofCallerPackage(config);
                    }
                }
            );

            XposedBridge.hookAllMethods(
                connectivityServiceClass,
                "getNetworkCapabilities",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        String callerPackage = resolveSpoofCallerPackage(config);
                        if (callerPackage != null) {
                            final NetworkCapabilities caps = (NetworkCapabilities) param.getResult();
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                final NetworkCapabilities newCaps = new NetworkCapabilities(caps);
                                sanitizeNetworkCapabilities(newCaps);
                                param.setResult(newCaps);
                                XposedAttackReporter.reportSystemEvent(
                                    callerPackage,
                                    XposedAttackVector.SYSTEM_NETWORK_CAPABILITIES,
                                    "getNetworkCapabilities"
                                );
                            }
                        }
                    }
                }
            );

            XposedBridge.hookAllMethods(
                connectivityServiceClass,
                "getLinkProperties",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        String callerPackage = resolveSpoofCallerPackage(config);
                        if (callerPackage != null) {
                            final LinkProperties props = (LinkProperties) param.getResult();
                            if (props != null && isTunnelInterface(props.getInterfaceName())) {
                                try {
                                    final Constructor<?> copyConstructor = LinkProperties.class.getConstructor(
                                        LinkProperties.class
                                    );
                                    final LinkProperties newProps = (LinkProperties) copyConstructor.newInstance(props);
                                    sanitizeLinkProperties(newProps);
                                    param.setResult(newProps);
                                    XposedAttackReporter.reportSystemEvent(
                                        callerPackage,
                                        XposedAttackVector.SYSTEM_LINK_PROPERTIES,
                                        normalizeVectorDetail(props.getInterfaceName())
                                    );
                                } catch (final Throwable t) {
                                    Log.e(LOG_TAG, "Failed to create a copy of LinkProperties. Returning null.", t);
                                    param.setResult(null);
                                }
                            }
                        }
                    }
                }
            );
        } catch (final Throwable t) {
            Log.e(LOG_TAG, "Failed to hook network services in system_server", t);
        }

        if (config.hideFromDumpsys) {
            final XC_MethodHook dumpHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    String callerPackage = resolveSpoofCallerPackage(config);
                    if (callerPackage != null) {
                        Log.i(LOG_TAG, "Filtering dumpsys output for UID " + Binder.getCallingUid());
                        final PrintWriter originalPw = (PrintWriter) param.args[1];
                        param.args[1] = new FilteringPrintWriter(originalPw);
                        XposedAttackReporter.reportSystemEvent(
                            callerPackage,
                            XposedAttackVector.SYSTEM_DUMPSYS,
                            normalizeVectorDetail(
                                param.method != null ? param.method.getDeclaringClass().getSimpleName() : "dump"
                            )
                        );
                    }
                }
            };

            try {
                final Class<?> networkManagementService = XposedHelpers.findClass(
                    "com.android.server.NetworkManagementService",
                    classLoader
                );
                XposedBridge.hookAllMethods(networkManagementService, "dump", dumpHook);
            } catch (final Throwable t) {
                Log.e(LOG_TAG, "Failed to hook NetworkManagementService.dump", t);
            }
            try {
                final Class<?> networkStatsService = XposedHelpers.findClass(
                    "com.android.server.net.NetworkStatsService",
                    classLoader
                );
                XposedBridge.hookAllMethods(networkStatsService, "dump", dumpHook);
            } catch (final Throwable t) {
                Log.e(LOG_TAG, "Failed to hook NetworkStatsService.dump", t);
            }
            try {
                final Class<?> connectivityService = XposedHelpers.findClass(
                    "com.android.server.ConnectivityService",
                    classLoader
                );
                XposedBridge.hookAllMethods(connectivityService, "dump", dumpHook);
            } catch (final Throwable t) {
                Log.e(LOG_TAG, "Failed to hook ConnectivityService.dump", t);
            }
        }
    }

    private static String[] getPackagesForUid(int uid) {
        try {
            Class<?> appGlobalsClass = Class.forName("android.app.AppGlobals");
            Method getPackageManagerMethod = appGlobalsClass.getMethod("getPackageManager");
            Object packageManager = getPackageManagerMethod.invoke(null);
            if (packageManager == null) {
                return null;
            }
            Method getPackagesForUidMethod = packageManager.getClass().getMethod("getPackagesForUid", int.class);
            Object result = getPackagesForUidMethod.invoke(packageManager, uid);
            return result instanceof String[] ? (String[]) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookInProcessApis(final ClassLoader classLoader, final ModuleConfig config) {
        hookNetworkCapabilities();
        hookLinkProperties();
        hookJavaNetworkInterfaces();
        hookSystemProperties();
        hookProcfs();

        if (config.nativeHookEnabled && NativeVpnDetectionHook.install()) {
            hookNativeLibraryLoads();
            hookKnownNativeInterfaceDetectors(classLoader);
        }
        if (config.hideVpnApps) {
            hookPackageManager(classLoader, config.hiddenVpnPackages);
        }
    }

    private static void hookNetworkCapabilities() {
        XposedHelpers.findAndHookMethod(
            NetworkCapabilities.class,
            "hasTransport",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isFrameworkNetworkInternalCall()) {
                        return;
                    }
                    if (
                        param.args != null &&
                        param.args.length == 1 &&
                        Integer.valueOf(NetworkCapabilities.TRANSPORT_VPN).equals(param.args[0]) &&
                        Boolean.TRUE.equals(param.getResult())
                    ) {
                        param.setResult(false);
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.NETWORK_CAPS_HAS_TRANSPORT_VPN, null);
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
                    if (isFrameworkNetworkInternalCall()) {
                        return;
                    }
                    Object transportInfo = param.getResult();
                    if (isVpnTransportInfo(transportInfo)) {
                        param.setResult(null);
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.NETWORK_CAPS_TRANSPORT_INFO, null);
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
                    if (isFrameworkNetworkInternalCall()) {
                        return;
                    }
                    String interfaceName = (String) param.getResult();
                    if (isTunnelInterface(interfaceName)) {
                        param.setResult(FALLBACK_INTERFACE);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.LINK_PROPERTIES_INTERFACE_NAME,
                            normalizeVectorDetail(interfaceName)
                        );
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
                        if (isFrameworkNetworkInternalCall()) {
                            return;
                        }
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
                        if (filtered.size() != list.size()) {
                            param.setResult(filtered);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.LINK_PROPERTIES_ALL_INTERFACES,
                                "hidden=" + (list.size() - filtered.size())
                            );
                        }
                    }
                }
            );
        } catch (Throwable ignored) {}

        XposedHelpers.findAndHookMethod(
            LinkProperties.class,
            "getRoutes",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isFrameworkNetworkInternalCall()) {
                        return;
                    }
                    Object result = param.getResult();
                    if (!(result instanceof List<?>)) {
                        return;
                    }
                    List<?> list = (List<?>) result;
                    List<RouteInfo> filtered = new ArrayList<>(list.size());
                    for (Object value : list) {
                        if (value instanceof RouteInfo && !isTunnelInterface(((RouteInfo) value).getInterface())) {
                            filtered.add((RouteInfo) value);
                        }
                    }
                    if (filtered.size() != list.size()) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.LINK_PROPERTIES_ROUTES,
                            "hidden=" + (list.size() - filtered.size())
                        );
                    }
                }
            }
        );

        XposedHelpers.findAndHookMethod(
            LinkProperties.class,
            "getDnsServers",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isFrameworkNetworkInternalCall()) {
                        return;
                    }
                    Object result = param.getResult();
                    if (!(result instanceof List<?>)) {
                        return;
                    }
                    List<?> list = (List<?>) result;
                    List<InetAddress> filtered = new ArrayList<>(list.size());
                    for (Object value : list) {
                        if (value instanceof InetAddress && !((InetAddress) value).isLoopbackAddress()) {
                            filtered.add((InetAddress) value);
                        }
                    }
                    if (filtered.size() != list.size()) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.LINK_PROPERTIES_DNS,
                            "hidden=" + (list.size() - filtered.size())
                        );
                    }
                }
            }
        );
    }

    private static void hookProcfs() {
        XposedHelpers.findAndHookConstructor(
            FileInputStream.class,
            String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isWebViewBootstrapCall()) {
                        return;
                    }
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    String path = (String) param.args[0];
                    if (path != null && path.startsWith("/proc/net/")) {
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.PROCFS_JAVA,
                            normalizeVectorDetail(path)
                        );
                        param.setThrowable(new FileNotFoundException(path + " (Permission denied)"));
                    }
                }
            }
        );
    }

    private static void hookSystemProperties() {
        XposedHelpers.findAndHookMethod(
            System.class,
            "getProperty",
            String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isWebViewBootstrapCall()) {
                        return;
                    }
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if (
                        "http.proxyHost".equals(key) ||
                        "http.proxyPort".equals(key) ||
                        "https.proxyHost".equals(key) ||
                        "https.proxyPort".equals(key) ||
                        "socksProxyHost".equals(key) ||
                        "socksProxyPort".equals(key)
                    ) {
                        param.setResult(null);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.SYSTEM_PROXY_PROPERTIES,
                            normalizeVectorDetail(key)
                        );
                    }
                }
            }
        );
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
                    boolean hiddenDetected = false;
                    while (enumeration.hasMoreElements()) {
                        Object next = enumeration.nextElement();
                        if (next instanceof NetworkInterface) {
                            NetworkInterface networkInterface = (NetworkInterface) next;
                            if (isTunnelInterface(networkInterface.getName())) {
                                hiddenDetected = true;
                            } else {
                                filtered.add(networkInterface);
                            }
                        }
                    }
                    param.setResult(Collections.enumeration(filtered));
                    if (hiddenDetected) {
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.NETWORK_INTERFACE_LIST, null);
                    }
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
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NETWORK_INTERFACE_BY_NAME,
                            normalizeVectorDetail((String) param.args[0])
                        );
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
                    if (filtered.size() != blocks.length) {
                        param.setResult(filtered.toArray(new String[0]));
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_GETIFADDRS,
                            "hidden=" + (blocks.length - filtered.size())
                        );
                    }
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
                    if (isWebViewBootstrapCall()) {
                        return;
                    }
                    Object filtered = filterPackageInfoList(param.getResult(), hiddenPackages);
                    if (filtered != param.getResult()) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.PACKAGE_MANAGER_INSTALLED_PACKAGES,
                            null
                        );
                    }
                }
            }
        );
        XposedBridge.hookAllMethods(
            packageManagerClass,
            "getInstalledApplications",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isWebViewBootstrapCall()) {
                        return;
                    }
                    Object filtered = filterApplicationInfoList(param.getResult(), hiddenPackages);
                    if (filtered != param.getResult()) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.PACKAGE_MANAGER_INSTALLED_APPLICATIONS,
                            null
                        );
                    }
                }
            }
        );

        XC_MethodHook hideSinglePackageHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (isWebViewBootstrapCall()) {
                    return;
                }
                String packageName = extractPackageName(
                    param.args != null && param.args.length > 0 ? param.args[0] : null
                );
                if (packageName != null && hiddenPackages.contains(packageName)) {
                    XposedAttackReporter.reportAppEvent(
                        "getPackageInfo".equals(param.method.getName())
                            ? XposedAttackVector.PACKAGE_MANAGER_PACKAGE_INFO
                            : XposedAttackVector.PACKAGE_MANAGER_APPLICATION_INFO,
                        normalizeVectorDetail(packageName)
                    );
                    param.setThrowable(new PackageManager.NameNotFoundException(packageName));
                }
            }
        };
        XposedBridge.hookAllMethods(packageManagerClass, "getPackageInfo", hideSinglePackageHook);
        XposedBridge.hookAllMethods(packageManagerClass, "getApplicationInfo", hideSinglePackageHook);

        XC_MethodHook hideVpnServiceAnnouncementsHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isWebViewBootstrapCall()) {
                    return;
                }
                Object filtered = filterResolveInfoList(param.getResult(), hiddenPackages, param.args);
                if (filtered != param.getResult()) {
                    param.setResult(filtered);
                    XposedAttackReporter.reportAppEvent(XposedAttackVector.PACKAGE_MANAGER_QUERY_INTENT_SERVICES, null);
                }
            }
        };
        XposedBridge.hookAllMethods(packageManagerClass, "queryIntentServices", hideVpnServiceAnnouncementsHook);
        XposedBridge.hookAllMethods(packageManagerClass, "queryIntentServicesAsUser", hideVpnServiceAnnouncementsHook);

        XposedBridge.hookAllMethods(
            packageManagerClass,
            "resolveService",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isWebViewBootstrapCall()) {
                        return;
                    }
                    ResolveInfo resolveInfo = filterResolveInfo(param.getResult(), hiddenPackages, param.args);
                    if (resolveInfo != param.getResult()) {
                        param.setResult(resolveInfo);
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.PACKAGE_MANAGER_RESOLVE_SERVICE, null);
                    }
                }
            }
        );
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
        return filtered.size() == source.size() ? value : filtered;
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
        return filtered.size() == source.size() ? value : filtered;
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

    private static Object filterResolveInfoList(Object value, Set<String> hiddenPackages, Object[] args) {
        if (!(value instanceof List<?>)) {
            return value;
        }
        List<?> source = (List<?>) value;
        List<ResolveInfo> filtered = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof ResolveInfo)) {
                continue;
            }
            ResolveInfo resolveInfo = filterResolveInfo(item, hiddenPackages, args);
            if (resolveInfo != null) {
                filtered.add(resolveInfo);
            }
        }
        return filtered.size() == source.size() ? value : filtered;
    }

    private static ResolveInfo filterResolveInfo(Object value, Set<String> hiddenPackages, Object[] args) {
        if (!(value instanceof ResolveInfo)) {
            return null;
        }
        ResolveInfo resolveInfo = (ResolveInfo) value;
        return shouldHideVpnServiceResolveInfo(resolveInfo, hiddenPackages, args) ? null : resolveInfo;
    }

    private static boolean shouldHideVpnServiceResolveInfo(
        ResolveInfo resolveInfo,
        Set<String> hiddenPackages,
        Object[] args
    ) {
        if (resolveInfo == null || hiddenPackages == null || hiddenPackages.isEmpty()) {
            return false;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (serviceInfo == null || !hiddenPackages.contains(serviceInfo.packageName)) {
            return false;
        }
        return isVpnServiceAnnouncement(args) || isVpnService(serviceInfo);
    }

    private static boolean isVpnServiceAnnouncement(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Intent)) {
            return false;
        }
        Intent intent = (Intent) args[0];
        return intent != null && VpnService.SERVICE_INTERFACE.equals(intent.getAction());
    }

    private static boolean isVpnService(ServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return false;
        }
        return VPN_SERVICE_PERMISSION.equals(serviceInfo.permission);
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

    @SuppressWarnings({ "PMD.AvoidCatchingGenericException", "unchecked" })
    private static void sanitizeLinkProperties(Object value) {
        if (!(value instanceof LinkProperties)) {
            return;
        }
        final LinkProperties props = (LinkProperties) value;

        try {
            final List<RouteInfo> originalRoutes = props.getRoutes();
            final List<RouteInfo> filteredRoutes = new ArrayList<>();
            if (originalRoutes != null) {
                for (final RouteInfo route : originalRoutes) {
                    if (route != null && !isTunnelInterface(route.getInterface())) {
                        filteredRoutes.add(route);
                    }
                }
            }
            final Method setRoutes = LinkProperties.class.getMethod("setRoutes", java.util.Collection.class);
            setRoutes.invoke(props, filteredRoutes);
        } catch (final Throwable t) {
            Log.e(LOG_TAG, "Failed to sanitize routes", t);
        }

        try {
            final List<InetAddress> originalDns = props.getDnsServers();
            final List<InetAddress> filteredDns = new ArrayList<>();
            if (originalDns != null) {
                for (final InetAddress dns : originalDns) {
                    if (dns != null && !dns.isLoopbackAddress()) {
                        filteredDns.add(dns);
                    }
                }
            }
            props.setDnsServers(filteredDns);
        } catch (final Throwable t) {
            Log.e(LOG_TAG, "Failed to sanitize DNS servers", t);
        }
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
            normalized.startsWith("utun") ||
            normalized.startsWith("ipsec") ||
            normalized.contains("wireguard")
        );
    }

    private static String normalizeVectorDetail(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
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

    private static boolean isWebViewBootstrapCall() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return false;
        }
        for (StackTraceElement element : stackTrace) {
            if (element == null) {
                continue;
            }
            String className = element.getClassName();
            if (className == null) {
                continue;
            }
            for (String prefix : WEBVIEW_STACK_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFrameworkNetworkInternalCall() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return false;
        }
        for (StackTraceElement element : stackTrace) {
            if (element == null) {
                continue;
            }
            String className = element.getClassName();
            if (className == null) {
                continue;
            }
            for (String prefix : FRAMEWORK_NETWORK_INTERNAL_STACK_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
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

    private static final class FilteringPrintWriter extends PrintWriter {

        FilteringPrintWriter(PrintWriter original) {
            super(original);
        }

        @Override
        public void println(String x) {
            if (x != null && containsTunnelInterface(x)) {
                return;
            }
            super.println(x);
        }

        private boolean containsTunnelInterface(String text) {
            if (text == null) {
                return false;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            return (
                normalized.contains("tun") ||
                normalized.contains("tap") ||
                normalized.contains("ppp") ||
                normalized.contains("wg") ||
                normalized.contains("utun") ||
                normalized.contains("ipsec") ||
                normalized.contains("wireguard")
            );
        }
    }

    private static final class ModuleConfig {

        final boolean enabled;
        final boolean allApps;
        final boolean nativeHookEnabled;
        final boolean hideVpnApps;
        final boolean hideFromDumpsys;
        final Set<String> targetPackages;
        final Set<String> hiddenVpnPackages;

        private ModuleConfig(
            boolean enabled,
            boolean allApps,
            boolean nativeHookEnabled,
            boolean hideVpnApps,
            boolean hideFromDumpsys,
            Set<String> targetPackages,
            Set<String> hiddenVpnPackages
        ) {
            this.enabled = enabled;
            this.allApps = allApps;
            this.nativeHookEnabled = nativeHookEnabled;
            this.hideVpnApps = hideVpnApps;
            this.hideFromDumpsys = hideFromDumpsys;
            this.targetPackages = targetPackages;
            this.hiddenVpnPackages = hiddenVpnPackages;
        }

        static ModuleConfig load() {
            try {
                final XSharedPreferences preferences = new XSharedPreferences(
                    MODULE_PACKAGE,
                    XposedModulePrefs.PREFS_NAME
                );
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
                    preferences.getBoolean(
                        XposedModulePrefs.KEY_HIDE_VPN_APPS,
                        XposedModulePrefs.DEFAULT_HIDE_VPN_APPS
                    ),
                    preferences.getBoolean(
                        XposedModulePrefs.KEY_HIDE_FROM_DUMPSYS,
                        XposedModulePrefs.DEFAULT_HIDE_FROM_DUMPSYS
                    ),
                    getPackages(preferences, XposedModulePrefs.KEY_TARGET_PACKAGES, ""),
                    getPackages(
                        preferences,
                        XposedModulePrefs.KEY_HIDDEN_VPN_PACKAGES,
                        XposedModulePrefs.DEFAULT_HIDDEN_VPN_PACKAGES
                    )
                );
            } catch (final Throwable t) {
                Log.e(LOG_TAG, "Failed to load module settings, module will be disabled.", t);
                return new ModuleConfig(
                    false,
                    false,
                    false,
                    false,
                    false,
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet()
                );
            }
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
