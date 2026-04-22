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
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
    private static final Set<String> HOOKED_NATIVE_BRIDGE_CLASSES = Collections.synchronizedSet(
        new LinkedHashSet<String>()
    );
    private static final Set<String> HOOKED_NATIVE_INTERFACE_PROBE_CLASSES = Collections.synchronizedSet(
        new LinkedHashSet<String>()
    );
    private static final Set<ClassLoader> SCANNED_CLASSLOADERS = Collections.synchronizedSet(
        Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>())
    );
    private static final Set<ClassLoader> SCANNED_NATIVE_INTERFACE_PROBE_CLASSLOADERS = Collections.synchronizedSet(
        Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>())
    );
    private static final Set<String> HOOKED_ICMP_PROBE_CLASSES = Collections.synchronizedSet(
        new LinkedHashSet<String>()
    );
    private static final Set<String> HOOKED_ICMP_CHECKER_CLASSES = Collections.synchronizedSet(
        new LinkedHashSet<String>()
    );
    private static final Set<ClassLoader> SCANNED_ICMP_PROBE_CLASSLOADERS = Collections.synchronizedSet(
        Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>())
    );
    private static final Set<ClassLoader> SCANNED_ICMP_CHECKER_CLASSLOADERS = Collections.synchronizedSet(
        Collections.newSetFromMap(new java.util.WeakHashMap<ClassLoader, Boolean>())
    );
    private static boolean icmpExecHooksInstalled;

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
        hookInProcessApis(loadPackageParam.classLoader, config, packageName);
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

    private static void hookInProcessApis(
        final ClassLoader classLoader,
        final ModuleConfig config,
        final String currentPackageName
    ) {
        hookNetworkCapabilities();
        hookLinkProperties();
        hookJavaNetworkInterfaces();
        hookSystemProperties();
        hookProcfs();
        hookIcmpSpoofingMitigation(classLoader);

        if (config.nativeHookEnabled) {
            hookKnownNativeInterfaceDetectors(classLoader);
            if (NativeVpnDetectionHook.install()) {
                hookNativeLibraryLoads();
            }
        }
        if (config.hideVpnApps) {
            hookPackageManager(classLoader, config.hiddenVpnPackages, currentPackageName);
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
                    if (isCallingOriginal()) {
                        return;
                    }
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
                    if (isCallingOriginal()) {
                        return;
                    }
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

        XposedHelpers.findAndHookMethod(
            NetworkInterface.class,
            "getByIndex",
            int.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isCallingOriginal()) {
                        return;
                    }
                    Object result = param.getResult();
                    if (!(result instanceof NetworkInterface)) {
                        return;
                    }
                    NetworkInterface networkInterface = (NetworkInterface) result;
                    if (isTunnelInterface(networkInterface.getName())) {
                        param.setResult(null);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NETWORK_INTERFACE_BY_NAME,
                            "index=" + param.args[0]
                        );
                    }
                }
            }
        );
    }

    private static void hookKnownNativeInterfaceDetectors(ClassLoader classLoader) {
        hookDexKitNativeBridgeMethods(classLoader);
        hookNativeInterfaceProbeClasses(classLoader);
        hookSignatureBasedNativeBridges(classLoader);

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

    private static void hookNativeInterfaceProbeClasses(@Nullable ClassLoader classLoader) {
        scanAndHookNativeInterfaceProbeClasses(classLoader);

        XC_MethodHook classLoadHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof Class<?>)) {
                    return;
                }
                Class<?> loadedClass = (Class<?>) result;
                if (classLoader == null || loadedClass.getClassLoader() != classLoader) {
                    return;
                }
                maybeHookNativeInterfaceProbeClass(loadedClass);
            }
        };

        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", classLoadHook);
        } catch (Throwable ignored) {}
    }

    private static void scanAndHookNativeInterfaceProbeClasses(@Nullable ClassLoader classLoader) {
        if (
            !(classLoader instanceof BaseDexClassLoader) ||
            !SCANNED_NATIVE_INTERFACE_PROBE_CLASSLOADERS.add(classLoader)
        ) {
            return;
        }
        try {
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) {
                return;
            }

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                return;
            }

            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField;
                try {
                    dexFileField = element.getClass().getDeclaredField("dexFile");
                } catch (NoSuchFieldException missingField) {
                    continue;
                }
                dexFileField.setAccessible(true);
                Object dexFileValue = dexFileField.get(element);
                if (!(dexFileValue instanceof DexFile)) {
                    continue;
                }
                DexFile dexFile = (DexFile) dexFileValue;
                Enumeration<String> entries = dexFile.entries();
                while (entries != null && entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    if (
                        className == null ||
                        className.startsWith("java.") ||
                        className.startsWith("android.") ||
                        className.startsWith("kotlin.") ||
                        className.startsWith("de.robv.android.xposed.") ||
                        className.startsWith("wings.v.") ||
                        HOOKED_NATIVE_INTERFACE_PROBE_CLASSES.contains(className)
                    ) {
                        continue;
                    }
                    Class<?> candidateClass = XposedHelpers.findClassIfExists(className, classLoader);
                    if (candidateClass != null) {
                        maybeHookNativeInterfaceProbeClass(candidateClass);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void maybeHookNativeInterfaceProbeClass(@Nullable Class<?> candidateClass) {
        if (candidateClass == null) {
            return;
        }
        String className = candidateClass.getName();
        if (
            className.startsWith("java.") ||
            className.startsWith("android.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("de.robv.android.xposed.") ||
            className.startsWith("wings.v.")
        ) {
            return;
        }
        if (
            !looksLikeNativeInterfaceProbeClass(candidateClass) || !HOOKED_NATIVE_INTERFACE_PROBE_CLASSES.add(className)
        ) {
            return;
        }
        Log.i(LOG_TAG, "Hooking native interface probe class: " + className);
        hookNativeInterfaceProbeMethods(candidateClass);
    }

    private static boolean looksLikeNativeInterfaceProbeClass(@NonNull Class<?> candidateClass) {
        return (
            hasMethod(candidateClass, "collectInterfaces") ||
            hasMethod(candidateClass, "parseIfAddrsRows") ||
            hasMethod(candidateClass, "parseIfAddrRow")
        );
    }

    private static void hookNativeInterfaceProbeMethods(@NonNull Class<?> probeClass) {
        XposedBridge.hookAllMethods(
            probeClass,
            "parseIfAddrRow",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null) {
                        return;
                    }
                    String name = extractModelName(result);
                    if (isTunnelInterface(name)) {
                        param.setResult(null);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_GETIFADDRS,
                            "row_hidden=" + normalizeVectorDetail(name)
                        );
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            probeClass,
            "parseIfAddrsRows",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof List<?>)) {
                        return;
                    }
                    List<?> source = (List<?>) result;
                    List<Object> filtered = new ArrayList<>(source.size());
                    int hidden = 0;
                    for (Object item : source) {
                        String name = extractModelName(item);
                        if (isTunnelInterface(name)) {
                            hidden++;
                            continue;
                        }
                        filtered.add(item);
                    }
                    if (hidden > 0) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_GETIFADDRS,
                            "rows_hidden=" + hidden
                        );
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            probeClass,
            "collectInterfaces",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof List<?>)) {
                        return;
                    }
                    List<?> source = (List<?>) result;
                    List<Object> filtered = new ArrayList<>(source.size());
                    int hidden = 0;
                    for (Object item : source) {
                        String name = extractModelName(item);
                        if (isTunnelInterface(name)) {
                            hidden++;
                            continue;
                        }
                        filtered.add(item);
                    }
                    if (hidden > 0) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_GETIFADDRS,
                            "list_hidden=" + hidden
                        );
                    }
                }
            }
        );
    }

    private static void hookIcmpSpoofingMitigation(@Nullable ClassLoader classLoader) {
        hookAllSystemPingExecutors();
        scanAndHookIcmpProbeClasses(classLoader);
        scanAndHookIcmpCheckerClasses(classLoader);

        XC_MethodHook classLoadHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof Class<?>)) {
                    return;
                }
                Class<?> loadedClass = (Class<?>) result;
                if (classLoader == null || loadedClass.getClassLoader() != classLoader) {
                    return;
                }
                maybeHookIcmpProbeClass(loadedClass);
                maybeHookIcmpCheckerClass(loadedClass);
            }
        };

        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", classLoadHook);
        } catch (Throwable ignored) {}
    }

    private static synchronized void hookAllSystemPingExecutors() {
        if (icmpExecHooksInstalled) {
            return;
        }
        XC_MethodHook execHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                applyIcmpExecutionMode(param.thisObject, param.args, param);
            }
        };

        XposedBridge.hookAllMethods(ProcessBuilder.class, "start", execHook);
        XposedBridge.hookAllMethods(Runtime.class, "exec", execHook);
        hookIfClassExists("java.lang.ProcessManager", "exec", execHook);
        hookIfClassExists("java.lang.ProcessImpl", "start", execHook);
        hookIfClassExists("java.lang.ProcessImpl", "startPipeline", execHook);
        icmpExecHooksInstalled = true;
    }

    private static void hookIfClassExists(
        @NonNull String className,
        @NonNull String methodName,
        @NonNull XC_MethodHook hook
    ) {
        try {
            Class<?> clazz = Class.forName(className);
            XposedBridge.hookAllMethods(clazz, methodName, hook);
        } catch (Throwable ignored) {}
    }

    private static void applyIcmpExecutionMode(
        @Nullable Object owner,
        @Nullable Object[] args,
        @NonNull XC_MethodHook.MethodHookParam param
    ) {
        List<String> command = extractExecutedCommand(owner, args);
        if (!isPingCommand(command)) {
            return;
        }

        String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
        if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
            return;
        }

        String address = command.isEmpty() ? "" : command.get(command.size() - 1);
        String binary = command.isEmpty() ? "ping" : command.get(0);
        String detail = normalizeVectorDetail(icmpSpoofingMode + ":" + binary + ":" + address);
        XposedBridge.log("WINGS V ICMP hook: mode=" + icmpSpoofingMode + " binary=" + binary + " addr=" + address);
        if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
            param.setThrowable(new IOException("ping not found"));
            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
            return;
        }

        if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
            param.setResult(new SyntheticPingProcess(buildSyntheticPingOutput(address), 1));
            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
        }
    }

    private static void scanAndHookIcmpProbeClasses(@Nullable ClassLoader classLoader) {
        if (!(classLoader instanceof BaseDexClassLoader) || !SCANNED_ICMP_PROBE_CLASSLOADERS.add(classLoader)) {
            return;
        }
        try {
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) {
                return;
            }

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                return;
            }

            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField;
                try {
                    dexFileField = element.getClass().getDeclaredField("dexFile");
                } catch (NoSuchFieldException missingField) {
                    continue;
                }
                dexFileField.setAccessible(true);
                Object dexFileValue = dexFileField.get(element);
                if (!(dexFileValue instanceof DexFile)) {
                    continue;
                }
                DexFile dexFile = (DexFile) dexFileValue;
                Enumeration<String> entries = dexFile.entries();
                while (entries != null && entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    if (
                        className == null ||
                        className.startsWith("java.") ||
                        className.startsWith("android.") ||
                        className.startsWith("kotlin.") ||
                        className.startsWith("de.robv.android.xposed.") ||
                        className.startsWith("wings.v.") ||
                        HOOKED_ICMP_PROBE_CLASSES.contains(className)
                    ) {
                        continue;
                    }
                    Class<?> candidateClass = XposedHelpers.findClassIfExists(className, classLoader);
                    if (candidateClass != null) {
                        maybeHookIcmpProbeClass(candidateClass);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scanAndHookIcmpCheckerClasses(@Nullable ClassLoader classLoader) {
        if (!(classLoader instanceof BaseDexClassLoader) || !SCANNED_ICMP_CHECKER_CLASSLOADERS.add(classLoader)) {
            return;
        }
        try {
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) {
                return;
            }

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                return;
            }

            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField;
                try {
                    dexFileField = element.getClass().getDeclaredField("dexFile");
                } catch (NoSuchFieldException missingField) {
                    continue;
                }
                dexFileField.setAccessible(true);
                Object dexFileValue = dexFileField.get(element);
                if (!(dexFileValue instanceof DexFile)) {
                    continue;
                }
                DexFile dexFile = (DexFile) dexFileValue;
                Enumeration<String> entries = dexFile.entries();
                while (entries != null && entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    if (
                        className == null ||
                        className.startsWith("java.") ||
                        className.startsWith("android.") ||
                        className.startsWith("kotlin.") ||
                        className.startsWith("de.robv.android.xposed.") ||
                        className.startsWith("wings.v.") ||
                        HOOKED_ICMP_CHECKER_CLASSES.contains(className)
                    ) {
                        continue;
                    }
                    Class<?> candidateClass = XposedHelpers.findClassIfExists(className, classLoader);
                    if (candidateClass != null) {
                        maybeHookIcmpCheckerClass(candidateClass);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void maybeHookIcmpProbeClass(@Nullable Class<?> candidateClass) {
        if (candidateClass == null) {
            return;
        }
        String className = candidateClass.getName();
        if (
            className.startsWith("java.") ||
            className.startsWith("android.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("de.robv.android.xposed.") ||
            className.startsWith("wings.v.")
        ) {
            return;
        }
        if (!looksLikeIcmpPingProbeClass(candidateClass) || !HOOKED_ICMP_PROBE_CLASSES.add(className)) {
            return;
        }
        Log.i(LOG_TAG, "Hooking ICMP probe class: " + className);
        hookIcmpPingProbeMethods(candidateClass);
    }

    private static void maybeHookIcmpCheckerClass(@Nullable Class<?> candidateClass) {
        if (candidateClass == null) {
            return;
        }
        String className = candidateClass.getName();
        if (
            className.startsWith("java.") ||
            className.startsWith("android.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("de.robv.android.xposed.") ||
            className.startsWith("wings.v.")
        ) {
            return;
        }
        if (!looksLikeIcmpCheckerClass(candidateClass) || !HOOKED_ICMP_CHECKER_CLASSES.add(className)) {
            return;
        }
        Log.i(LOG_TAG, "Hooking ICMP checker class: " + className);
        hookIcmpCheckerMethods(candidateClass);
    }

    private static boolean looksLikeIcmpPingProbeClass(@NonNull Class<?> candidateClass) {
        return (
            hasMethod(candidateClass, "probe") ||
            hasMethod(candidateClass, "runCommand") ||
            hasMethod(candidateClass, "parse") ||
            hasMethod(candidateClass, "buildCommand")
        );
    }

    private static boolean looksLikeIcmpCheckerClass(@NonNull Class<?> candidateClass) {
        return (
            hasMethod(candidateClass, "check") &&
            (hasMethod(candidateClass, "formatRtt") ||
                hasMethod(candidateClass, "unsupportedResult") ||
                hasMethod(candidateClass, "suspiciousEvidence"))
        );
    }

    private static void hookIcmpPingProbeMethods(@NonNull Class<?> probeClass) {
        XposedBridge.hookAllMethods(
            probeClass,
            "parse",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }

                    String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                        return;
                    }

                    String address = (String) param.args[0];
                    String detail = normalizeVectorDetail(icmpSpoofingMode + ":parse:" + address);
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
                        param.setThrowable(new IOException("ping not found"));
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        return;
                    }

                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
                        Object synthetic = buildSyntheticPingResult(param.method, address);
                        if (synthetic != null) {
                            param.setResult(synthetic);
                            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        }
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            probeClass,
            "probe",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }

                    String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                        return;
                    }

                    String address = (String) param.args[0];
                    String detail = normalizeVectorDetail(icmpSpoofingMode + ":probe:" + address);
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
                        param.setThrowable(new IOException("ping not found"));
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        return;
                    }

                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
                        Object synthetic = buildSyntheticPingResult(param.method, address);
                        if (synthetic != null) {
                            param.setResult(synthetic);
                            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        }
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            probeClass,
            "runCommand",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    List<String> command = extractCommandFromRunCommandArgs(param.args);
                    if (!isPingCommand(command)) {
                        return;
                    }

                    String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                        return;
                    }

                    String address = command.isEmpty() ? "" : command.get(command.size() - 1);
                    String binary = command.isEmpty() ? "ping" : command.get(0);
                    String detail = normalizeVectorDetail(icmpSpoofingMode + ":" + binary + ":" + address);
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
                        param.setThrowable(new IOException("ping not found"));
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        return;
                    }

                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
                        Object synthetic = buildSyntheticCommandResult(param.method, address);
                        if (synthetic != null) {
                            param.setResult(synthetic);
                            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                        }
                    }
                }
            }
        );
    }

    private static void hookIcmpCheckerMethods(@NonNull Class<?> checkerClass) {
        XposedBridge.hookAllMethods(
            checkerClass,
            "check",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                    if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                        return;
                    }

                    Object result = param.getResult();
                    Object sanitized = buildSanitizedCategoryResult(result);
                    if (sanitized != null) {
                        param.setResult(sanitized);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.ICMP_SPOOFING_PROBE,
                            normalizeVectorDetail(icmpSpoofingMode + ":check")
                        );
                    }
                }
            }
        );
    }

    @Nullable
    private static Object buildSyntheticPingResult(@Nullable Object methodValue, @Nullable String address) {
        if (!(methodValue instanceof Method)) {
            return null;
        }
        try {
            Method method = (Method) methodValue;
            ClassLoader classLoader = method.getDeclaringClass().getClassLoader();
            if (classLoader == null) {
                return null;
            }
            Class<?> resultClass = XposedHelpers.findClassIfExists(
                method.getDeclaringClass().getName() + "$PingResult",
                classLoader
            );
            if (resultClass == null) {
                return null;
            }
            return XposedHelpers.newInstance(
                resultClass,
                address == null ? "" : address,
                3,
                0,
                null,
                null,
                null,
                1,
                buildSyntheticPingOutput(address)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object buildSanitizedCategoryResult(@Nullable Object original) {
        if (original == null) {
            return null;
        }
        try {
            Class<?> resultClass = original.getClass();
            Method getNameMethod = resultClass.getMethod("getName");
            Object name = getNameMethod.invoke(original);
            if (!(name instanceof String)) {
                return null;
            }
            for (Constructor<?> constructor : resultClass.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 9) {
                    continue;
                }
                constructor.setAccessible(true);
                return constructor.newInstance(
                    name,
                    false,
                    Collections.emptyList(),
                    false,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null
                );
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    private static Object buildSyntheticCommandResult(@Nullable Object methodValue, @Nullable String address) {
        if (!(methodValue instanceof Method)) {
            return null;
        }
        try {
            Method method = (Method) methodValue;
            Class<?> returnType = method.getReturnType();
            Constructor<?> constructor = returnType.getDeclaredConstructor(int.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(1, buildSyntheticPingOutput(address));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static String buildSyntheticPingOutput(@Nullable String address) {
        String normalizedAddress = TextUtils.isEmpty(address) ? "0.0.0.0" : address;
        return (
            "PING " +
            normalizedAddress +
            " (" +
            normalizedAddress +
            "): 56 data bytes\n" +
            "--- " +
            normalizedAddress +
            " ping statistics ---\n" +
            "3 packets transmitted, 0 packets received, 100% packet loss\n"
        );
    }

    @NonNull
    private static List<String> extractExecutedCommand(@Nullable Object owner, @Nullable Object[] args) {
        if (owner instanceof ProcessBuilder) {
            try {
                Object value = XposedHelpers.callMethod(owner, "command");
                if (value instanceof List<?>) {
                    return sanitizeCommandList((List<?>) value);
                }
            } catch (Throwable ignored) {}
        }

        if (args == null || args.length == 0) {
            return Collections.emptyList();
        }
        Object firstArg = args[0];
        if (firstArg instanceof List<?>) {
            return sanitizeCommandList((List<?>) firstArg);
        }
        if (firstArg instanceof String[]) {
            return sanitizeCommandArray((String[]) firstArg);
        }
        if (firstArg instanceof String) {
            return splitCommandString((String) firstArg);
        }
        return Collections.emptyList();
    }

    @NonNull
    private static List<String> extractCommandFromRunCommandArgs(@Nullable Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof List<?>)) {
            return Collections.emptyList();
        }
        return sanitizeCommandList((List<?>) args[0]);
    }

    @NonNull
    private static List<String> sanitizeCommandList(@NonNull List<?> command) {
        List<String> sanitized = new ArrayList<>(command.size());
        for (Object value : command) {
            if (value instanceof String && !TextUtils.isEmpty((String) value)) {
                sanitized.add(((String) value).trim());
            }
        }
        return sanitized;
    }

    @NonNull
    private static List<String> sanitizeCommandArray(@NonNull String[] command) {
        List<String> sanitized = new ArrayList<>(command.length);
        for (String value : command) {
            if (!TextUtils.isEmpty(value)) {
                sanitized.add(value.trim());
            }
        }
        return sanitized;
    }

    @NonNull
    private static List<String> splitCommandString(@Nullable String command) {
        if (TextUtils.isEmpty(command)) {
            return Collections.emptyList();
        }
        String[] parts = command.trim().split("\\s+");
        return sanitizeCommandArray(parts);
    }

    private static boolean isPingCommand(@NonNull List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String binary = command.get(0);
        return ("ping".equals(binary) || "/system/bin/ping".equals(binary) || binary.endsWith("/ping"));
    }

    @Nullable
    private static String extractModelName(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object name = value.getClass().getMethod("getName").invoke(value);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {}
        try {
            Field field = value.getClass().getDeclaredField("name");
            field.setAccessible(true);
            Object name = field.get(value);
            return name instanceof String ? (String) name : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static final class SyntheticPingProcess extends java.lang.Process {

        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final int exitCode;

        SyntheticPingProcess(@NonNull String output, int exitCode) {
            this.stdout = new ByteArrayInputStream(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {}
    }

    private static void hookDexKitNativeBridgeMethods(@Nullable ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        String apkPath = resolveHostApkPath(classLoader);
        if (apkPath == null) {
            return;
        }
        Object bridge = null;
        try {
            Class<?> bridgeClass = Class.forName("org.luckypray.dexkit.DexKitBridge");
            bridge = bridgeClass.getMethod("create", String.class).invoke(null, apkPath);
            hookDexKitMethod(
                bridge,
                classLoader,
                "getIfAddrs",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof String[])) {
                            return;
                        }
                        String[] rows = (String[]) result;
                        List<String> filtered = new ArrayList<>(rows.length);
                        int hidden = 0;
                        for (String row : rows) {
                            String name = extractNativeRowInterfaceName(row);
                            if (isTunnelInterface(name)) {
                                hidden++;
                                continue;
                            }
                            filtered.add(row);
                        }
                        if (hidden > 0) {
                            param.setResult(filtered.toArray(new String[0]));
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_GETIFADDRS,
                                "hidden=" + hidden
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "ifNameToIndex",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1 || !(param.args[0] instanceof String)) {
                            return;
                        }
                        String name = (String) param.args[0];
                        if (isTunnelInterface(name)) {
                            param.setResult(0);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_IF_NAMETOINDEX,
                                normalizeVectorDetail(name)
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "readProcFile",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                            return;
                        }
                        Object result = param.getResult();
                        if (!(result instanceof String)) {
                            return;
                        }
                        String path = (String) param.args[0];
                        String filtered = filterNativeProcContent(path, (String) result);
                        if (filtered != null && !filtered.equals(result)) {
                            param.setResult(filtered);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.PROCFS_JAVA,
                                normalizeVectorDetail(path)
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "netlinkRouteDump",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof String[])) {
                            return;
                        }
                        String[] rows = (String[]) result;
                        List<String> filtered = new ArrayList<>(rows.length);
                        int hidden = 0;
                        for (String row : rows) {
                            if (shouldHideNativeNetlinkRouteRow(row)) {
                                hidden++;
                                continue;
                            }
                            filtered.add(row);
                        }
                        if (hidden > 0) {
                            param.setResult(filtered.toArray(new String[0]));
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_NETLINK_ROUTE,
                                "hidden=" + hidden
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "interfaceDump",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof String[])) {
                            return;
                        }
                        String[] blocks = (String[]) result;
                        List<String> filtered = new ArrayList<>(blocks.length);
                        int hidden = 0;
                        for (String block : blocks) {
                            if (isTunnelInterface(extractInterfaceName(block))) {
                                hidden++;
                                continue;
                            }
                            filtered.add(block);
                        }
                        if (hidden > 0) {
                            param.setResult(filtered.toArray(new String[0]));
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_GETIFADDRS,
                                "dump_hidden=" + hidden
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "collectInterfaces",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof List<?>)) {
                            return;
                        }
                        List<?> source = (List<?>) result;
                        List<Object> filtered = new ArrayList<>(source.size());
                        int hidden = 0;
                        for (Object item : source) {
                            String name = extractModelName(item);
                            if (isTunnelInterface(name)) {
                                hidden++;
                                continue;
                            }
                            filtered.add(item);
                        }
                        if (hidden > 0) {
                            param.setResult(filtered);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_GETIFADDRS,
                                "dexkit_list_hidden=" + hidden
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "parseIfAddrRow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (result == null) {
                            return;
                        }
                        String name = extractModelName(result);
                        if (isTunnelInterface(name)) {
                            param.setResult(null);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_GETIFADDRS,
                                "dexkit_row_hidden=" + normalizeVectorDetail(name)
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "parseIfAddrsRows",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof List<?>)) {
                            return;
                        }
                        List<?> source = (List<?>) result;
                        List<Object> filtered = new ArrayList<>(source.size());
                        int hidden = 0;
                        for (Object item : source) {
                            String name = extractModelName(item);
                            if (isTunnelInterface(name)) {
                                hidden++;
                                continue;
                            }
                            filtered.add(item);
                        }
                        if (hidden > 0) {
                            param.setResult(filtered);
                            XposedAttackReporter.reportAppEvent(
                                XposedAttackVector.NATIVE_GETIFADDRS,
                                "dexkit_rows_hidden=" + hidden
                            );
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "probe",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                            return;
                        }

                        String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                            return;
                        }

                        String address = (String) param.args[0];
                        String detail = normalizeVectorDetail(icmpSpoofingMode + ":dexkit_probe:" + address);
                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
                            param.setThrowable(new IOException("ping not found"));
                            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                            return;
                        }

                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
                            Object synthetic = buildSyntheticPingResult(param.method, address);
                            if (synthetic != null) {
                                param.setResult(synthetic);
                                XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                            }
                        }
                    }
                }
            );
            hookDexKitMethod(
                bridge,
                classLoader,
                "runCommand",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        List<String> command = extractCommandFromRunCommandArgs(param.args);
                        if (!isPingCommand(command)) {
                            return;
                        }

                        String icmpSpoofingMode = ModuleConfig.load().icmpSpoofingMode;
                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_DISABLED.equals(icmpSpoofingMode)) {
                            return;
                        }

                        String address = command.isEmpty() ? "" : command.get(command.size() - 1);
                        String binary = command.isEmpty() ? "ping" : command.get(0);
                        String detail = normalizeVectorDetail(
                            icmpSpoofingMode + ":dexkit_cmd:" + binary + ":" + address
                        );
                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_PING_NOT_FOUND.equals(icmpSpoofingMode)) {
                            param.setThrowable(new IOException("ping not found"));
                            XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                            return;
                        }

                        if (XposedModulePrefs.ICMP_SPOOFING_MODE_EMPTY_RESPONSE.equals(icmpSpoofingMode)) {
                            Object synthetic = buildSyntheticCommandResult(param.method, address);
                            if (synthetic != null) {
                                param.setResult(synthetic);
                                XposedAttackReporter.reportAppEvent(XposedAttackVector.ICMP_SPOOFING_PROBE, detail);
                            }
                        }
                    }
                }
            );
        } catch (Throwable throwable) {
            Log.w(LOG_TAG, "DexKit bridge discovery failed", throwable);
        } finally {
            if (bridge != null) {
                try {
                    bridge.getClass().getMethod("close").invoke(bridge);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void hookDexKitMethod(
        @NonNull Object bridge,
        @NonNull ClassLoader classLoader,
        @NonNull String methodName,
        @NonNull XC_MethodHook hook
    ) {
        try {
            Class<?> findMethodClass = Class.forName("org.luckypray.dexkit.query.FindMethod");
            Class<?> matcherClass = Class.forName("org.luckypray.dexkit.query.matchers.MethodMatcher");
            Object findMethod = findMethodClass.getConstructor().newInstance();
            Object matcher = matcherClass.getConstructor().newInstance();
            matcherClass.getMethod("setName", String.class).invoke(matcher, methodName);
            findMethodClass.getMethod("setMatcher", matcherClass).invoke(findMethod, matcher);
            Object methodsResult = bridge
                .getClass()
                .getMethod("findMethod", findMethodClass)
                .invoke(bridge, findMethod);
            if (!(methodsResult instanceof java.util.List<?>)) {
                return;
            }
            java.util.List<?> methods = (java.util.List<?>) methodsResult;
            if (methods == null) {
                return;
            }
            for (Object methodData : methods) {
                if (methodData == null) {
                    continue;
                }
                Method reflected = (Method) XposedHelpers.callMethod(methodData, "getMethodInstance", classLoader);
                if (reflected != null) {
                    Log.i(
                        LOG_TAG,
                        "DexKit hooking native bridge method: " +
                            reflected.getDeclaringClass().getName() +
                            "#" +
                            reflected.getName()
                    );
                    XposedBridge.hookMethod(reflected, hook);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Nullable
    private static String resolveHostApkPath(@Nullable ClassLoader classLoader) {
        if (!(classLoader instanceof BaseDexClassLoader)) {
            return null;
        }
        try {
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) {
                return null;
            }
            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                return null;
            }
            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field pathField;
                try {
                    pathField = element.getClass().getDeclaredField("path");
                } catch (NoSuchFieldException missingField) {
                    continue;
                }
                pathField.setAccessible(true);
                Object pathValue = pathField.get(element);
                if (pathValue instanceof java.io.File) {
                    java.io.File file = (java.io.File) pathValue;
                    if (file.isFile() && file.getName().endsWith(".apk")) {
                        return file.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void hookSignatureBasedNativeBridges(ClassLoader classLoader) {
        scanAndHookNativeBridgeClasses(classLoader);

        XC_MethodHook classLoadHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof Class<?>)) {
                    return;
                }
                Class<?> loadedClass = (Class<?>) result;
                if (loadedClass.getClassLoader() != classLoader) {
                    return;
                }
                maybeHookNativeBridgeClass(loadedClass);
            }
        };

        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", classLoadHook);
        } catch (Throwable ignored) {}
    }

    private static void scanAndHookNativeBridgeClasses(@Nullable ClassLoader classLoader) {
        if (!(classLoader instanceof BaseDexClassLoader) || !SCANNED_CLASSLOADERS.add(classLoader)) {
            return;
        }
        try {
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) {
                return;
            }

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) {
                return;
            }

            for (Object element : dexElements) {
                if (element == null) {
                    continue;
                }
                Field dexFileField;
                try {
                    dexFileField = element.getClass().getDeclaredField("dexFile");
                } catch (NoSuchFieldException missingField) {
                    continue;
                }
                dexFileField.setAccessible(true);
                Object dexFileValue = dexFileField.get(element);
                if (!(dexFileValue instanceof DexFile)) {
                    continue;
                }
                DexFile dexFile = (DexFile) dexFileValue;
                Enumeration<String> entries = dexFile.entries();
                while (entries != null && entries.hasMoreElements()) {
                    String className = entries.nextElement();
                    if (
                        className == null ||
                        className.startsWith("java.") ||
                        className.startsWith("android.") ||
                        className.startsWith("kotlin.") ||
                        className.startsWith("de.robv.android.xposed.") ||
                        className.startsWith("wings.v.") ||
                        HOOKED_NATIVE_BRIDGE_CLASSES.contains(className)
                    ) {
                        continue;
                    }
                    Class<?> candidateClass = XposedHelpers.findClassIfExists(className, classLoader);
                    if (candidateClass != null) {
                        maybeHookNativeBridgeClass(candidateClass);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void maybeHookNativeBridgeClass(@Nullable Class<?> candidateClass) {
        if (candidateClass == null) {
            return;
        }
        String className = candidateClass.getName();
        if (
            className.startsWith("java.") ||
            className.startsWith("android.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("de.robv.android.xposed.") ||
            className.startsWith("wings.v.")
        ) {
            return;
        }
        if (!looksLikeNativeDetectorBridge(candidateClass) || !HOOKED_NATIVE_BRIDGE_CLASSES.add(className)) {
            return;
        }
        Log.i(LOG_TAG, "Hooking signature-based native bridge: " + className);
        hookGenericNativeBridgeMethods(candidateClass);
    }

    private static boolean looksLikeNativeDetectorBridge(@NonNull Class<?> candidateClass) {
        return (
            hasMethod(candidateClass, "getIfAddrs") ||
            hasMethod(candidateClass, "interfaceDump") ||
            hasMethod(candidateClass, "netlinkRouteDump") ||
            hasMethod(candidateClass, "readProcFile") ||
            hasMethod(candidateClass, "ifNameToIndex")
        );
    }

    private static boolean hasMethod(@NonNull Class<?> candidateClass, @NonNull String methodName) {
        for (Method method : candidateClass.getDeclaredMethods()) {
            if (method != null && methodName.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void hookGenericNativeBridgeMethods(@NonNull Class<?> bridgeClass) {
        XposedBridge.hookAllMethods(
            bridgeClass,
            "getIfAddrs",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String[])) {
                        return;
                    }
                    String[] rows = (String[]) result;
                    List<String> filtered = new ArrayList<>(rows.length);
                    int hidden = 0;
                    for (String row : rows) {
                        String name = extractNativeRowInterfaceName(row);
                        if (isTunnelInterface(name)) {
                            hidden++;
                            continue;
                        }
                        filtered.add(row);
                    }
                    if (hidden > 0) {
                        param.setResult(filtered.toArray(new String[0]));
                        XposedAttackReporter.reportAppEvent(XposedAttackVector.NATIVE_GETIFADDRS, "hidden=" + hidden);
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            bridgeClass,
            "ifNameToIndex",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String name = (String) param.args[0];
                    if (isTunnelInterface(name)) {
                        param.setResult(0);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_IF_NAMETOINDEX,
                            normalizeVectorDetail(name)
                        );
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            bridgeClass,
            "readProcFile",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    Object result = param.getResult();
                    if (!(result instanceof String)) {
                        return;
                    }
                    String path = (String) param.args[0];
                    String filtered = filterNativeProcContent(path, (String) result);
                    if (filtered != null && !filtered.equals(result)) {
                        param.setResult(filtered);
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.PROCFS_JAVA,
                            normalizeVectorDetail(path)
                        );
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            bridgeClass,
            "netlinkRouteDump",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String[])) {
                        return;
                    }
                    String[] rows = (String[]) result;
                    List<String> filtered = new ArrayList<>(rows.length);
                    int hidden = 0;
                    for (String row : rows) {
                        if (shouldHideNativeNetlinkRouteRow(row)) {
                            hidden++;
                            continue;
                        }
                        filtered.add(row);
                    }
                    if (hidden > 0) {
                        param.setResult(filtered.toArray(new String[0]));
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_NETLINK_ROUTE,
                            "hidden=" + hidden
                        );
                    }
                }
            }
        );

        XposedBridge.hookAllMethods(
            bridgeClass,
            "interfaceDump",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof String[])) {
                        return;
                    }
                    String[] blocks = (String[]) result;
                    List<String> filtered = new ArrayList<>(blocks.length);
                    int hidden = 0;
                    for (String block : blocks) {
                        if (isTunnelInterface(extractInterfaceName(block))) {
                            hidden++;
                            continue;
                        }
                        filtered.add(block);
                    }
                    if (hidden > 0) {
                        param.setResult(filtered.toArray(new String[0]));
                        XposedAttackReporter.reportAppEvent(
                            XposedAttackVector.NATIVE_GETIFADDRS,
                            "dump_hidden=" + hidden
                        );
                    }
                }
            }
        );
    }

    @Nullable
    private static String extractNativeRowInterfaceName(@Nullable String row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        int separator = row.indexOf('|');
        if (separator <= 0) {
            return row;
        }
        return row.substring(0, separator);
    }

    @Nullable
    private static String filterNativeProcContent(@Nullable String path, @Nullable String content) {
        if (path == null || content == null || content.isEmpty()) {
            return content;
        }
        if (path.endsWith("/route")) {
            StringBuilder filtered = new StringBuilder();
            String[] lines = content.split("\n");
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (index == 0 || !startsWithTunnelInterface(line)) {
                    filtered.append(line);
                    if (index < lines.length - 1) {
                        filtered.append('\n');
                    }
                }
            }
            return filtered.toString();
        }
        if (path.endsWith("/ipv6_route")) {
            StringBuilder filtered = new StringBuilder();
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (!startsWithTunnelInterface(line)) {
                    filtered.append(line).append('\n');
                }
            }
            return filtered.toString().trim();
        }
        if (path.endsWith("/dev")) {
            StringBuilder filtered = new StringBuilder();
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (!startsWithTunnelInterface(line)) {
                    filtered.append(line).append('\n');
                }
            }
            return filtered.toString().trim();
        }
        return content;
    }

    private static boolean startsWithTunnelInterface(@Nullable String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        int separator = trimmed.indexOf(':');
        String candidate = separator > 0 ? trimmed.substring(0, separator).trim() : trimmed.split("\\s+")[0];
        return isTunnelInterface(candidate);
    }

    private static boolean shouldHideNativeNetlinkRouteRow(@Nullable String row) {
        if (row == null || !row.startsWith("route|")) {
            return false;
        }
        String[] parts = row.split("\\|");
        Integer outputIfIndex = null;
        String deviceName = null;
        for (String part : parts) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator);
            String value = part.substring(separator + 1);
            if ("dev".equals(key)) {
                deviceName = value;
            } else if ("oif".equals(key)) {
                try {
                    outputIfIndex = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (isTunnelInterface(deviceName)) {
            return true;
        }
        return outputIfIndex != null && isTunnelInterface(resolveRawInterfaceNameByIndex(outputIfIndex));
    }

    @Nullable
    private static String resolveRawInterfaceNameByIndex(int index) {
        if (index <= 0) {
            return null;
        }
        NetworkInterface networkInterface = callOriginal(() -> NetworkInterface.getByIndex(index));
        if (networkInterface == null) {
            return null;
        }
        try {
            return networkInterface.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookPackageManager(
        ClassLoader classLoader,
        Set<String> hiddenPackages,
        @Nullable String currentPackageName
    ) {
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
                    Object filtered = filterPackageInfoList(param.getResult(), hiddenPackages, currentPackageName);
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
                    Object filtered = filterApplicationInfoList(param.getResult(), hiddenPackages, currentPackageName);
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
                if (packageName != null && packageName.equals(currentPackageName)) {
                    return;
                }
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

    private static Object filterPackageInfoList(
        Object value,
        Set<String> hiddenPackages,
        @Nullable String currentPackageName
    ) {
        if (!(value instanceof List<?>)) {
            return value;
        }
        List<?> source = (List<?>) value;
        List<PackageInfo> filtered = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof PackageInfo)) {
                continue;
            }
            PackageInfo packageInfo = (PackageInfo) item;
            String packageName = packageInfo.packageName;
            if (packageName != null && packageName.equals(currentPackageName)) {
                filtered.add(packageInfo);
                continue;
            }
            if (!hiddenPackages.contains(packageName)) {
                filtered.add(packageInfo);
            }
        }
        return filtered.size() == source.size() ? value : filtered;
    }

    private static Object filterApplicationInfoList(
        Object value,
        Set<String> hiddenPackages,
        @Nullable String currentPackageName
    ) {
        if (!(value instanceof List<?>)) {
            return value;
        }
        List<?> source = (List<?>) value;
        List<ApplicationInfo> filtered = new ArrayList<>(source.size());
        for (Object item : source) {
            if (!(item instanceof ApplicationInfo)) {
                continue;
            }
            ApplicationInfo applicationInfo = (ApplicationInfo) item;
            String packageName = applicationInfo.packageName;
            if (packageName != null && packageName.equals(currentPackageName)) {
                filtered.add(applicationInfo);
                continue;
            }
            if (!hiddenPackages.contains(packageName)) {
                filtered.add(applicationInfo);
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
        final String icmpSpoofingMode;
        final boolean hideVpnApps;
        final boolean hideFromDumpsys;
        final Set<String> targetPackages;
        final Set<String> hiddenVpnPackages;

        private ModuleConfig(
            boolean enabled,
            boolean allApps,
            boolean nativeHookEnabled,
            String icmpSpoofingMode,
            boolean hideVpnApps,
            boolean hideFromDumpsys,
            Set<String> targetPackages,
            Set<String> hiddenVpnPackages
        ) {
            this.enabled = enabled;
            this.allApps = allApps;
            this.nativeHookEnabled = nativeHookEnabled;
            this.icmpSpoofingMode = icmpSpoofingMode;
            this.hideVpnApps = hideVpnApps;
            this.hideFromDumpsys = hideFromDumpsys;
            this.targetPackages = targetPackages;
            this.hiddenVpnPackages = hiddenVpnPackages;
        }

        private static final Object CACHE_LOCK = new Object();
        private static final long CACHE_TTL_NS = 1_000_000_000L;

        @Nullable
        private static volatile ModuleConfig cached;

        private static volatile long cachedAtNs;

        static ModuleConfig load() {
            ModuleConfig snapshot = cached;
            long now = System.nanoTime();
            if (snapshot != null && now - cachedAtNs < CACHE_TTL_NS) {
                return snapshot;
            }
            synchronized (CACHE_LOCK) {
                snapshot = cached;
                if (snapshot != null && System.nanoTime() - cachedAtNs < CACHE_TTL_NS) {
                    return snapshot;
                }
                ModuleConfig fresh = doLoad();
                cached = fresh;
                cachedAtNs = System.nanoTime();
                return fresh;
            }
        }

        private static ModuleConfig doLoad() {
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
                    XposedModulePrefs.normalizeIcmpSpoofingMode(
                        getSystemString(
                            XposedModulePrefs.PROP_ICMP_SPOOFING_MODE,
                            preferences.getString(
                                XposedModulePrefs.KEY_ICMP_SPOOFING_MODE,
                                XposedModulePrefs.DEFAULT_ICMP_SPOOFING_MODE
                            )
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
                    XposedModulePrefs.DEFAULT_ICMP_SPOOFING_MODE,
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

        private static String getSystemString(String key, String fallback) {
            try {
                Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
                Method getMethod = systemPropertiesClass.getMethod("get", String.class);
                Object value = getMethod.invoke(null, key);
                if (!(value instanceof String)) {
                    return fallback;
                }
                String normalized = ((String) value).trim();
                return normalized.isEmpty() ? fallback : normalized;
            } catch (Throwable ignored) {
                return fallback;
            }
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
