#include <android/log.h>
#include <ifaddrs.h>
#include <jni.h>
#include <net/if.h>
#include <pthread.h>
#include <string>
#include <unordered_map>

#include "xhook.h"

#define LOG_TAG "WINGS-XposedNative"

static int (*orig_getifaddrs)(ifaddrs **) = nullptr;
static void (*orig_freeifaddrs)(ifaddrs *) = nullptr;
static unsigned int (*orig_if_nametoindex)(const char *) = nullptr;
static char *(*orig_if_indextoname)(unsigned int, char *) = nullptr;
static pthread_mutex_t g_hidden_lists_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_map<ifaddrs *, ifaddrs *> g_hidden_lists;
static bool g_installed = false;
static constexpr const char *kHookedLibrariesPattern = ".*";

static bool starts_with(const std::string &value, const char *prefix) {
    return value.rfind(prefix, 0) == 0;
}

static bool is_tunnel_interface(const char *name) {
    if (name == nullptr || name[0] == '\0') {
        return false;
    }

    std::string lowered(name);
    for (char &ch : lowered) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }

    return starts_with(lowered, "tun")
            || starts_with(lowered, "tap")
            || starts_with(lowered, "ppp")
            || starts_with(lowered, "wg")
            || starts_with(lowered, "utun")
            || starts_with(lowered, "ipsec")
            || starts_with(lowered, "xfrm")
            || starts_with(lowered, "zt")
            || lowered.find("vpn") != std::string::npos;
}

static void append_node(ifaddrs **head, ifaddrs **tail, ifaddrs *node) {
    node->ifa_next = nullptr;
    if (*tail != nullptr) {
        (*tail)->ifa_next = node;
    } else {
        *head = node;
    }
    *tail = node;
}

static void remember_hidden_list(ifaddrs *visible_head, ifaddrs *hidden_head) {
    if (visible_head == nullptr || hidden_head == nullptr) {
        return;
    }
    pthread_mutex_lock(&g_hidden_lists_lock);
    g_hidden_lists[visible_head] = hidden_head;
    pthread_mutex_unlock(&g_hidden_lists_lock);
}

static ifaddrs *take_hidden_list(ifaddrs *visible_head) {
    if (visible_head == nullptr) {
        return nullptr;
    }

    pthread_mutex_lock(&g_hidden_lists_lock);
    auto it = g_hidden_lists.find(visible_head);
    if (it == g_hidden_lists.end()) {
        pthread_mutex_unlock(&g_hidden_lists_lock);
        return nullptr;
    }

    ifaddrs *hidden_head = it->second;
    g_hidden_lists.erase(it);
    pthread_mutex_unlock(&g_hidden_lists_lock);
    return hidden_head;
}

static int filter_ifaddrs(ifaddrs **ifap) {
    if (ifap == nullptr || *ifap == nullptr) {
        return 0;
    }

    ifaddrs *visible_head = nullptr;
    ifaddrs *visible_tail = nullptr;
    ifaddrs *hidden_head = nullptr;
    ifaddrs *hidden_tail = nullptr;
    for (ifaddrs *it = *ifap; it != nullptr;) {
        ifaddrs *next = it->ifa_next;
        if (is_tunnel_interface(it->ifa_name)) {
            append_node(&hidden_head, &hidden_tail, it);
        } else {
            append_node(&visible_head, &visible_tail, it);
        }
        it = next;
    }

    if (hidden_head != nullptr) {
        remember_hidden_list(visible_head, hidden_head);
        *ifap = visible_head;
    }
    return 0;
}

static int proxy_getifaddrs(ifaddrs **ifap) {
    if (orig_getifaddrs == nullptr) {
        return -1;
    }

    int result = orig_getifaddrs(ifap);
    if (result == 0) {
        filter_ifaddrs(ifap);
    }
    return result;
}

static void proxy_freeifaddrs(ifaddrs *ifap) {
    if (orig_freeifaddrs != nullptr) {
        ifaddrs *hidden_head = take_hidden_list(ifap);
        if (ifap != nullptr) {
            orig_freeifaddrs(ifap);
        }
        if (hidden_head != nullptr) {
            orig_freeifaddrs(hidden_head);
        }
    }
}

static unsigned int proxy_if_nametoindex(const char *ifname) {
    if (is_tunnel_interface(ifname)) {
        return 0;
    }
    return orig_if_nametoindex != nullptr ? orig_if_nametoindex(ifname) : 0;
}

static char *proxy_if_indextoname(unsigned int ifindex, char *ifname) {
    char *result = orig_if_indextoname != nullptr ? orig_if_indextoname(ifindex, ifname) : nullptr;
    if (is_tunnel_interface(result)) {
        return nullptr;
    }
    return result;
}

static int refresh_hooks() {
    int refresh_result = xhook_refresh(0);
    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "xhook refresh=%d getifaddrs=%p",
            refresh_result,
            reinterpret_cast<void *>(orig_getifaddrs)
    );
    return refresh_result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_wings_v_xposed_NativeVpnDetectionHook_nativeInstall(JNIEnv *, jclass) {
    if (g_installed) {
        return JNI_TRUE;
    }

    xhook_enable_debug(0);
    xhook_enable_sigsegv_protection(1);

    int result = 0;
    result |= xhook_register(
            kHookedLibrariesPattern,
            "getifaddrs",
            reinterpret_cast<void *>(proxy_getifaddrs),
            reinterpret_cast<void **>(&orig_getifaddrs)
    );
    result |= xhook_register(
            kHookedLibrariesPattern,
            "freeifaddrs",
            reinterpret_cast<void *>(proxy_freeifaddrs),
            reinterpret_cast<void **>(&orig_freeifaddrs)
    );
    result |= xhook_register(
            kHookedLibrariesPattern,
            "if_nametoindex",
            reinterpret_cast<void *>(proxy_if_nametoindex),
            reinterpret_cast<void **>(&orig_if_nametoindex)
    );
    result |= xhook_register(
            kHookedLibrariesPattern,
            "if_indextoname",
            reinterpret_cast<void *>(proxy_if_indextoname),
            reinterpret_cast<void **>(&orig_if_indextoname)
    );

    int refresh_result = refresh_hooks();

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "xhook installed register=%d refresh=%d pattern=%s",
            result,
            refresh_result,
            kHookedLibrariesPattern
    );

    g_installed = result == 0;
    return g_installed ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_wings_v_xposed_NativeVpnDetectionHook_nativeRefresh(JNIEnv *, jclass) {
    if (!g_installed) {
        return;
    }
    refresh_hooks();
}
