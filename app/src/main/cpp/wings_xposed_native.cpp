#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <jni.h>
#include <limits.h>
#include <linux/inet_diag.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/sock_diag.h>
#include <linux/sockios.h>
#include <net/if.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/system_properties.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <sys/vfs.h>
#include <time.h>
#include <unistd.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "xhook.h"
#ifdef WINGS_HAS_SHADOWHOOK
#include "shadowhook.h"
#endif

#define LOG_TAG "WINGS-XposedNative"
#define FALLBACK_IFACE "wlan0"

// ---------------------------------------------------------------------------
// Original function pointers
// ---------------------------------------------------------------------------

static int (*orig_getifaddrs)(ifaddrs **) = nullptr;
static void (*orig_freeifaddrs)(ifaddrs *) = nullptr;
static unsigned int (*orig_if_nametoindex)(const char *) = nullptr;
static char *(*orig_if_indextoname)(unsigned int, char *) = nullptr;
static struct if_nameindex *(*orig_if_nameindex)(void) = nullptr;
static FILE *(*orig_fopen)(const char *, const char *) = nullptr;
static int (*orig_fclose)(FILE *) = nullptr;
static int (*orig_open)(const char *, int, ...) = nullptr;
static int (*orig_openat)(int, const char *, int, ...) = nullptr;
static int (*orig_ioctl)(int, unsigned long, ...) = nullptr;
static int (*orig_socket)(int, int, int) = nullptr;
static int (*orig_close)(int) = nullptr;
static ssize_t (*orig_read)(int, void *, size_t) = nullptr;
static ssize_t (*orig___read_chk)(int, void *, size_t, size_t) = nullptr;
static ssize_t (*orig_recv)(int, void *, size_t, int) = nullptr;
static ssize_t (*orig_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *) = nullptr;
static ssize_t (*orig___recvfrom_chk)(int, void *, size_t, size_t, int, struct sockaddr *, socklen_t *) = nullptr;
static ssize_t (*orig_recvmsg)(int, struct msghdr *, int) = nullptr;
static int (*orig_recvmmsg)(int, struct mmsghdr *, unsigned int, int, struct timespec *) = nullptr;
static DIR *(*orig_opendir)(const char *) = nullptr;
static int (*orig_closedir)(DIR *) = nullptr;
static struct dirent *(*orig_readdir)(DIR *) = nullptr;
static struct dirent64 *(*orig_readdir64)(DIR *) = nullptr;
static int (*orig_access)(const char *, int) = nullptr;
static int (*orig_faccessat)(int, const char *, int, int) = nullptr;
static int (*orig_stat)(const char *, struct stat *) = nullptr;
static int (*orig_lstat)(const char *, struct stat *) = nullptr;
static int (*orig_fstatat)(int, const char *, struct stat *, int) = nullptr;
#if defined(__NR_statx)
static int (*orig_statx)(int, const char *, int, unsigned int, void *) = nullptr;
#endif
static int (*orig_statfs)(const char *, struct statfs *) = nullptr;

static JavaVM *g_vm = nullptr;
static jclass g_reporter_class = nullptr;
static jmethodID g_report_method = nullptr;

// ---------------------------------------------------------------------------
// Synchronized state
// ---------------------------------------------------------------------------

static pthread_mutex_t g_hidden_lists_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_map<ifaddrs *, ifaddrs *> g_hidden_lists;

static pthread_mutex_t g_netlink_fds_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_set<int> g_netlink_route_fds;
static std::unordered_set<int> g_netlink_sock_diag_fds;

struct ProcfsFd {
    std::vector<char> filtered;
    size_t offset;
};
static pthread_mutex_t g_procfs_fds_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_map<int, ProcfsFd *> g_procfs_fds;

static pthread_mutex_t g_dir_paths_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_map<DIR *, std::string> g_dir_paths;

static pthread_mutex_t g_ifindex_cache_lock = PTHREAD_MUTEX_INITIALIZER;
static std::unordered_set<int> g_tun_ifindex_cache;
static std::unordered_set<int> g_nontun_ifindex_cache;
static time_t g_ifindex_cache_expires_at = 0;
static constexpr time_t kIfindexCacheTtlSeconds = 2;

static bool g_installed = false;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

static constexpr const char *kHookedLibrariesPattern = ".*\\.so$";
static constexpr const char *kProcfsHookModeProp = "persist.wingsv.xposed.procfs_hook_mode";
static constexpr const char *kInlineHooksProp = "persist.wingsv.xposed.inline_hooks";
static constexpr const char *kSysClassNetPrefix = "/sys/class/net/";
static constexpr const char *kSysClassNetDir = "/sys/class/net";
static constexpr const char *kProcNetPrefix = "/proc/net/";
static constexpr const char *kProcSelfNetPrefix = "/proc/self/net/";
static constexpr uid_t kFirstApplicationUid = 10000;
static constexpr size_t kMaxProcfsFileBytes = 1024 * 1024;

static constexpr const char *kCriticalInfrastructureProcesses[] = {
        "system", "android", "system_server",
        "com.android.systemui", "com.android.phone",
        "com.android.networkstack.process", "com.android.networkstack",
        "com.google.android.networkstack.process", "com.google.android.networkstack",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller", "com.sec.android.app.packageinstaller",
        "com.android.webview", "com.google.android.webview",
        "com.google.android.trichromelibrary", "com.google.android.setupwizard",
        "com.samsung.android.setupwizard", "com.sec.android.app.SecSetupWizard",
        "com.android.managedprovisioning", "com.android.vending",
        "com.google.android.gms", "com.google.android.gsf", "com.google.process.gservices",
        "com.sec.imsservice", "com.sec.epdg", "com.samsung.android.mcfds",
};
static constexpr const char *kCriticalInfrastructurePrefixes[] = {
        "com.android.providers.", "com.google.android.providers.",
        "com.qualcomm.qti.", "com.qualcomm.qcril", "org.codeaurora.ims",
        "com.mediatek.", "com.sec.ims", "com.sec.epdg", "com.sec.phone",
        "com.samsung.android.telephony", "com.samsung.android.network",
};
static constexpr const char *kCriticalInfrastructureKeywords[] = {
        "ril", "qcril", "radio", "telephony", "ims", "epdg",
};

enum ProcfsHookMode {
    PROCFS_HOOK_MODE_DISABLED = 0,
    PROCFS_HOOK_MODE_FILE_NOT_FOUND = 1,
    PROCFS_HOOK_MODE_NO_ACCESS = 2,
    PROCFS_HOOK_MODE_FILTER = 3,
};

enum ProcfsKind {
    PROCFS_KIND_NONE = 0,
    PROCFS_KIND_ROUTE_V4,
    PROCFS_KIND_ROUTE_V6,
    PROCFS_KIND_IF_INET6,
    PROCFS_KIND_DEV,
};

// ---------------------------------------------------------------------------
// Forward declarations
// ---------------------------------------------------------------------------

static bool is_tunnel_interface(const char *name);
static bool is_tunnel_interface_or_alias(const char *name);
static bool read_ifindex_for_interface(const char *name, int *out_ifindex);
static char *write_fallback_interface_name(char *buffer);
static ProcfsHookMode get_procfs_hook_mode();
static void report_native_event(const char *vector, const char *detail);
static bool is_tun_ifindex_cached(int ifindex);
static void invalidate_ifindex_cache();

// ---------------------------------------------------------------------------
// Helpers: strings, paths, critical process detection
// ---------------------------------------------------------------------------

static bool starts_with(const std::string &value, const char *prefix) {
    return value.rfind(prefix, 0) == 0;
}

static std::string to_lower_ascii(std::string value) {
    for (char &ch : value) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }
    return value;
}

static bool matches_critical_name(const std::string &value, const char *candidate) {
    if (value.empty() || candidate == nullptr || candidate[0] == '\0') {
        return false;
    }
    return value == candidate
            || starts_with(value, (std::string(candidate) + ":").c_str())
            || starts_with(value, (std::string(candidate) + ".").c_str());
}

static std::string get_current_process_name() {
    char buffer[512] = {0};
    int fd = orig_open != nullptr
            ? orig_open("/proc/self/cmdline", O_RDONLY, 0)
            : open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) {
        return {};
    }
    ssize_t bytes_read = orig_read != nullptr
            ? orig_read(fd, buffer, sizeof(buffer) - 1)
            : read(fd, buffer, sizeof(buffer) - 1);
    if (orig_close != nullptr) {
        orig_close(fd);
    } else {
        close(fd);
    }
    if (bytes_read <= 0) {
        return {};
    }
    buffer[bytes_read] = '\0';
    return std::string(buffer);
}

static bool is_critical_infrastructure_process() {
    if (getuid() < kFirstApplicationUid || geteuid() < kFirstApplicationUid) {
        return true;
    }
    const std::string process_name = get_current_process_name();
    if (process_name.empty()) {
        return false;
    }
    for (const char *candidate : kCriticalInfrastructureProcesses) {
        if (matches_critical_name(process_name, candidate)) {
            return true;
        }
    }
    for (const char *candidate : kCriticalInfrastructurePrefixes) {
        if (starts_with(process_name, candidate)) {
            return true;
        }
    }
    const std::string normalized_process_name = to_lower_ascii(process_name);
    for (const char *keyword : kCriticalInfrastructureKeywords) {
        if (normalized_process_name.find(keyword) != std::string::npos) {
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Path classification
// ---------------------------------------------------------------------------

static bool is_proc_net_path(const char *path) {
    if (path == nullptr) {
        return false;
    }
    return strncmp(path, kProcNetPrefix, strlen(kProcNetPrefix)) == 0
            || strncmp(path, kProcSelfNetPrefix, strlen(kProcSelfNetPrefix)) == 0;
}

static ProcfsKind classify_procfs_path(const char *path) {
    if (!is_proc_net_path(path)) {
        return PROCFS_KIND_NONE;
    }
    const char *tail = strrchr(path, '/');
    if (tail == nullptr) {
        return PROCFS_KIND_NONE;
    }
    tail++;
    if (strcmp(tail, "route") == 0) return PROCFS_KIND_ROUTE_V4;
    if (strcmp(tail, "ipv6_route") == 0) return PROCFS_KIND_ROUTE_V6;
    if (strcmp(tail, "if_inet6") == 0) return PROCFS_KIND_IF_INET6;
    if (strcmp(tail, "dev") == 0) return PROCFS_KIND_DEV;
    return PROCFS_KIND_NONE;
}

static bool is_tunnel_sysfs_path(const char *path) {
    if (path == nullptr || strncmp(path, kSysClassNetPrefix, strlen(kSysClassNetPrefix)) != 0) {
        return false;
    }
    const char *interface_name = path + strlen(kSysClassNetPrefix);
    if (*interface_name == '\0') {
        return false;
    }
    const char *separator = strchr(interface_name, '/');
    std::string candidate = separator == nullptr
            ? std::string(interface_name)
            : std::string(interface_name, static_cast<size_t>(separator - interface_name));
    return is_tunnel_interface(candidate.c_str());
}

static bool is_tun_device_path(const char *path) {
    if (path == nullptr) {
        return false;
    }
    return strcmp(path, "/dev/tun") == 0
            || strcmp(path, "/dev/net/tun") == 0
            || strncmp(path, "/dev/tun", 8) == 0;
}

static ProcfsHookMode get_procfs_hook_mode() {
    char value[PROP_VALUE_MAX] = {0};
    int length = __system_property_get(kProcfsHookModeProp, value);
    if (length <= 0) {
        return PROCFS_HOOK_MODE_DISABLED;
    }
    if (strcmp(value, "filter") == 0) return PROCFS_HOOK_MODE_FILTER;
    if (strcmp(value, "no_access") == 0) return PROCFS_HOOK_MODE_NO_ACCESS;
    if (strcmp(value, "file_not_found") == 0) return PROCFS_HOOK_MODE_FILE_NOT_FOUND;
    return PROCFS_HOOK_MODE_DISABLED;
}

static bool should_block_native_path(const char *path, bool *is_procfs) {
    if (is_procfs != nullptr) {
        *is_procfs = false;
    }
    if (is_proc_net_path(path)) {
        if (is_procfs != nullptr) {
            *is_procfs = true;
        }
        ProcfsHookMode mode = get_procfs_hook_mode();
        return mode == PROCFS_HOOK_MODE_FILE_NOT_FOUND || mode == PROCFS_HOOK_MODE_NO_ACCESS;
    }
    return is_tunnel_sysfs_path(path);
}

static int blocked_path_errno(bool is_procfs) {
    if (!is_procfs) {
        return ENOENT;
    }
    switch (get_procfs_hook_mode()) {
        case PROCFS_HOOK_MODE_NO_ACCESS: return EACCES;
        case PROCFS_HOOK_MODE_FILE_NOT_FOUND: return ENOENT;
        default: return 0;
    }
}

// ---------------------------------------------------------------------------
// Interface helpers
// ---------------------------------------------------------------------------

static bool is_tunnel_interface(const char *name) {
    if (name == nullptr || name[0] == '\0') {
        return false;
    }
    std::string lowered = to_lower_ascii(name);
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

static bool is_tunnel_interface_or_alias(const char *name) {
    if (is_tunnel_interface(name)) {
        return true;
    }
    if (name == nullptr || orig_if_indextoname == nullptr) {
        return false;
    }
    const std::string lowered = to_lower_ascii(name);
    if (!starts_with(lowered, "if") || lowered.size() <= 2) {
        return false;
    }
    const char *index_part = lowered.c_str() + 2;
    char *end_ptr = nullptr;
    unsigned long parsed_index = strtoul(index_part, &end_ptr, 10);
    if (end_ptr == nullptr || *end_ptr != '\0' || parsed_index == 0 || parsed_index > UINT_MAX) {
        return false;
    }
    char resolved_name[IF_NAMESIZE] = {0};
    char *resolved = orig_if_indextoname(static_cast<unsigned int>(parsed_index), resolved_name);
    return resolved != nullptr && is_tunnel_interface(resolved);
}

static char *write_fallback_interface_name(char *buffer) {
    if (buffer == nullptr) {
        return nullptr;
    }
    strncpy(buffer, FALLBACK_IFACE, IF_NAMESIZE - 1);
    buffer[IF_NAMESIZE - 1] = '\0';
    return buffer;
}

static bool read_ifindex_for_interface(const char *name, int *out_ifindex) {
    if (name == nullptr || out_ifindex == nullptr) {
        return false;
    }
    char path[PATH_MAX] = {0};
    snprintf(path, sizeof(path), "%s%s/ifindex", kSysClassNetPrefix, name);
    int fd = orig_open != nullptr ? orig_open(path, O_RDONLY, 0) : open(path, O_RDONLY);
    if (fd < 0) {
        return false;
    }
    char buffer[32] = {0};
    ssize_t bytes_read = orig_read != nullptr
            ? orig_read(fd, buffer, sizeof(buffer) - 1)
            : read(fd, buffer, sizeof(buffer) - 1);
    if (orig_close != nullptr) {
        orig_close(fd);
    } else {
        close(fd);
    }
    if (bytes_read <= 0) {
        return false;
    }
    buffer[bytes_read] = '\0';
    *out_ifindex = atoi(buffer);
    return *out_ifindex > 0;
}

// ---------------------------------------------------------------------------
// Ifindex cache
// ---------------------------------------------------------------------------

static void invalidate_ifindex_cache() {
    pthread_mutex_lock(&g_ifindex_cache_lock);
    g_tun_ifindex_cache.clear();
    g_nontun_ifindex_cache.clear();
    g_ifindex_cache_expires_at = 0;
    pthread_mutex_unlock(&g_ifindex_cache_lock);
}

static void rebuild_ifindex_cache_locked() {
    g_tun_ifindex_cache.clear();
    g_nontun_ifindex_cache.clear();

    auto do_opendir = orig_opendir != nullptr ? orig_opendir : &opendir;
    auto do_readdir = orig_readdir != nullptr ? orig_readdir : &readdir;
    auto do_closedir = orig_closedir != nullptr ? orig_closedir : &closedir;
    DIR *dir = do_opendir(kSysClassNetDir);
    if (dir != nullptr) {
        struct dirent *entry = nullptr;
        while ((entry = do_readdir(dir)) != nullptr) {
            const char *name = entry->d_name;
            if (name == nullptr || name[0] == '.') continue;
            int ifindex = 0;
            if (!read_ifindex_for_interface(name, &ifindex)) continue;
            if (is_tunnel_interface(name)) {
                g_tun_ifindex_cache.insert(ifindex);
            } else {
                g_nontun_ifindex_cache.insert(ifindex);
            }
        }
        do_closedir(dir);
    }

    g_ifindex_cache_expires_at = time(nullptr) + kIfindexCacheTtlSeconds;
}

static bool is_tun_ifindex_cached(int ifindex) {
    if (ifindex <= 0) {
        return false;
    }
    pthread_mutex_lock(&g_ifindex_cache_lock);
    time_t now = time(nullptr);
    if (now >= g_ifindex_cache_expires_at
            || (g_tun_ifindex_cache.empty() && g_nontun_ifindex_cache.empty())) {
        rebuild_ifindex_cache_locked();
    }
    bool is_tun = g_tun_ifindex_cache.find(ifindex) != g_tun_ifindex_cache.end();
    bool is_known_nontun = g_nontun_ifindex_cache.find(ifindex) != g_nontun_ifindex_cache.end();
    pthread_mutex_unlock(&g_ifindex_cache_lock);

    if (is_tun) return true;
    if (is_known_nontun) return false;

    if (orig_if_indextoname != nullptr) {
        char resolved[IF_NAMESIZE] = {0};
        char *name = orig_if_indextoname(static_cast<unsigned int>(ifindex), resolved);
        if (name != nullptr && is_tunnel_interface_or_alias(name)) {
            pthread_mutex_lock(&g_ifindex_cache_lock);
            g_tun_ifindex_cache.insert(ifindex);
            pthread_mutex_unlock(&g_ifindex_cache_lock);
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// Event reporter
// ---------------------------------------------------------------------------

static void report_native_event(const char *vector, const char *detail) {
    if (g_vm == nullptr || vector == nullptr || g_reporter_class == nullptr || g_report_method == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            return;
        }
        attached = true;
    }
    jstring vector_value = env->NewStringUTF(vector);
    jstring detail_value = env->NewStringUTF(detail != nullptr ? detail : "");
    env->CallStaticVoidMethod(g_reporter_class, g_report_method, vector_value, detail_value);
    env->DeleteLocalRef(vector_value);
    env->DeleteLocalRef(detail_value);
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

// ---------------------------------------------------------------------------
// Netlink fd tracking
// ---------------------------------------------------------------------------

static void remember_netlink_fd(int fd, bool is_sock_diag) {
    if (fd < 0) return;
    pthread_mutex_lock(&g_netlink_fds_lock);
    if (is_sock_diag) {
        g_netlink_sock_diag_fds.insert(fd);
    } else {
        g_netlink_route_fds.insert(fd);
    }
    pthread_mutex_unlock(&g_netlink_fds_lock);
}

static void forget_netlink_fd(int fd) {
    if (fd < 0) return;
    pthread_mutex_lock(&g_netlink_fds_lock);
    g_netlink_route_fds.erase(fd);
    g_netlink_sock_diag_fds.erase(fd);
    pthread_mutex_unlock(&g_netlink_fds_lock);
}

static bool is_netlink_route_fd(int fd) {
    if (fd < 0) return false;
    pthread_mutex_lock(&g_netlink_fds_lock);
    bool tracked = g_netlink_route_fds.find(fd) != g_netlink_route_fds.end();
    pthread_mutex_unlock(&g_netlink_fds_lock);
    return tracked;
}

static bool is_netlink_sock_diag_fd(int fd) {
    if (fd < 0) return false;
    pthread_mutex_lock(&g_netlink_fds_lock);
    bool tracked = g_netlink_sock_diag_fds.find(fd) != g_netlink_sock_diag_fds.end();
    pthread_mutex_unlock(&g_netlink_fds_lock);
    return tracked;
}

// ---------------------------------------------------------------------------
// Procfs fd tracking and line filter
// ---------------------------------------------------------------------------

static bool should_drop_procfs_line(ProcfsKind kind, const char *line, size_t length, bool is_header) {
    if (is_header) return false;
    switch (kind) {
        case PROCFS_KIND_ROUTE_V4: {
            size_t i = 0;
            while (i < length && line[i] != ' ' && line[i] != '\t' && line[i] != '\n') i++;
            if (i == 0 || i > IFNAMSIZ) return false;
            char iface[IFNAMSIZ + 1];
            memcpy(iface, line, i);
            iface[i] = '\0';
            return is_tunnel_interface(iface);
        }
        case PROCFS_KIND_ROUTE_V6:
        case PROCFS_KIND_IF_INET6: {
            size_t end = length;
            while (end > 0 && (line[end - 1] == '\n' || line[end - 1] == ' ' || line[end - 1] == '\t')) end--;
            size_t start = end;
            while (start > 0 && line[start - 1] != ' ' && line[start - 1] != '\t') start--;
            size_t token_len = end - start;
            if (token_len == 0 || token_len > IFNAMSIZ) return false;
            char iface[IFNAMSIZ + 1];
            memcpy(iface, line + start, token_len);
            iface[token_len] = '\0';
            return is_tunnel_interface(iface);
        }
        case PROCFS_KIND_DEV: {
            size_t start = 0;
            while (start < length && (line[start] == ' ' || line[start] == '\t')) start++;
            size_t i = start;
            while (i < length && line[i] != ':' && line[i] != '\n') i++;
            if (i == start || i - start > IFNAMSIZ) return false;
            char iface[IFNAMSIZ + 1];
            memcpy(iface, line + start, i - start);
            iface[i - start] = '\0';
            return is_tunnel_interface(iface);
        }
        default:
            return false;
    }
}

static bool procfs_kind_has_header(ProcfsKind kind) {
    return kind == PROCFS_KIND_ROUTE_V4 || kind == PROCFS_KIND_DEV;
}

static ProcfsFd *build_filtered_procfs(int fd, ProcfsKind kind) {
    if (orig_read == nullptr) return nullptr;
    std::vector<char> raw;
    raw.reserve(8192);
    char chunk[8192];
    ssize_t n;
    while ((n = orig_read(fd, chunk, sizeof(chunk))) > 0) {
        if (raw.size() + static_cast<size_t>(n) > kMaxProcfsFileBytes) {
            return nullptr;
        }
        raw.insert(raw.end(), chunk, chunk + n);
    }
    if (n < 0) {
        return nullptr;
    }

    std::vector<char> out;
    out.reserve(raw.size());
    size_t pos = 0;
    bool header_used = !procfs_kind_has_header(kind);
    bool filtered_any = false;
    while (pos < raw.size()) {
        size_t line_end = pos;
        while (line_end < raw.size() && raw[line_end] != '\n') line_end++;
        size_t line_length = (line_end < raw.size() ? line_end + 1 : line_end) - pos;
        bool is_header = !header_used;
        if (is_header) header_used = true;
        if (should_drop_procfs_line(kind, raw.data() + pos, line_length, is_header)) {
            filtered_any = true;
        } else {
            out.insert(out.end(), raw.data() + pos, raw.data() + pos + line_length);
        }
        pos = line_end < raw.size() ? line_end + 1 : line_end;
    }

    if (!filtered_any) {
        return nullptr;
    }
    report_native_event("native_procfs_filter", "filtered");

    ProcfsFd *state = new ProcfsFd();
    state->filtered = std::move(out);
    state->offset = 0;
    return state;
}

static void try_track_procfs_fd(int fd, const char *path) {
    if (fd < 0 || path == nullptr) return;
    if (get_procfs_hook_mode() != PROCFS_HOOK_MODE_FILTER) return;
    ProcfsKind kind = classify_procfs_path(path);
    if (kind == PROCFS_KIND_NONE) return;

    ProcfsFd *state = build_filtered_procfs(fd, kind);
    if (state == nullptr) {
        lseek(fd, 0, SEEK_SET);
        return;
    }
    lseek(fd, 0, SEEK_END);

    pthread_mutex_lock(&g_procfs_fds_lock);
    auto it = g_procfs_fds.find(fd);
    if (it != g_procfs_fds.end()) {
        delete it->second;
        g_procfs_fds.erase(it);
    }
    g_procfs_fds[fd] = state;
    pthread_mutex_unlock(&g_procfs_fds_lock);
}

static ssize_t procfs_serve_read(int fd, void *buf, size_t count) {
    pthread_mutex_lock(&g_procfs_fds_lock);
    auto it = g_procfs_fds.find(fd);
    if (it == g_procfs_fds.end()) {
        pthread_mutex_unlock(&g_procfs_fds_lock);
        return -2; // "not tracked"
    }
    ProcfsFd *state = it->second;
    size_t remaining = state->filtered.size() > state->offset
            ? state->filtered.size() - state->offset
            : 0;
    size_t to_copy = remaining < count ? remaining : count;
    if (to_copy > 0) {
        memcpy(buf, state->filtered.data() + state->offset, to_copy);
        state->offset += to_copy;
    }
    pthread_mutex_unlock(&g_procfs_fds_lock);
    return static_cast<ssize_t>(to_copy);
}

static void forget_procfs_fd(int fd) {
    if (fd < 0) return;
    pthread_mutex_lock(&g_procfs_fds_lock);
    auto it = g_procfs_fds.find(fd);
    if (it != g_procfs_fds.end()) {
        delete it->second;
        g_procfs_fds.erase(it);
    }
    pthread_mutex_unlock(&g_procfs_fds_lock);
}

// ---------------------------------------------------------------------------
// Directory path tracking
// ---------------------------------------------------------------------------

static void remember_dir_path(DIR *dir, const char *path) {
    if (dir == nullptr || path == nullptr) return;
    pthread_mutex_lock(&g_dir_paths_lock);
    g_dir_paths[dir] = path;
    pthread_mutex_unlock(&g_dir_paths_lock);
}

static std::string take_dir_path(DIR *dir) {
    if (dir == nullptr) return {};
    pthread_mutex_lock(&g_dir_paths_lock);
    auto it = g_dir_paths.find(dir);
    std::string path = it != g_dir_paths.end() ? it->second : std::string();
    if (it != g_dir_paths.end()) g_dir_paths.erase(it);
    pthread_mutex_unlock(&g_dir_paths_lock);
    return path;
}

static bool dir_path_is_sysfs_net(DIR *dir) {
    if (dir == nullptr) return false;
    pthread_mutex_lock(&g_dir_paths_lock);
    auto it = g_dir_paths.find(dir);
    bool matches = false;
    if (it != g_dir_paths.end()) {
        matches = it->second == kSysClassNetDir || it->second == "/sys/class/net/";
    }
    pthread_mutex_unlock(&g_dir_paths_lock);
    return matches;
}

// ---------------------------------------------------------------------------
// getifaddrs / freeifaddrs
// ---------------------------------------------------------------------------

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
    if (hidden_head == nullptr) return;
    pthread_mutex_lock(&g_hidden_lists_lock);
    auto it = g_hidden_lists.find(visible_head);
    if (it != g_hidden_lists.end()) {
        ifaddrs *stale = it->second;
        g_hidden_lists.erase(it);
        pthread_mutex_unlock(&g_hidden_lists_lock);
        if (orig_freeifaddrs != nullptr && stale != nullptr) orig_freeifaddrs(stale);
        pthread_mutex_lock(&g_hidden_lists_lock);
    }
    g_hidden_lists[visible_head] = hidden_head;
    pthread_mutex_unlock(&g_hidden_lists_lock);
}

static ifaddrs *take_hidden_list(ifaddrs *visible_head) {
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
        if (is_tunnel_interface_or_alias(it->ifa_name)) {
            append_node(&hidden_head, &hidden_tail, it);
        } else {
            append_node(&visible_head, &visible_tail, it);
        }
        it = next;
    }
    if (hidden_head != nullptr) {
        report_native_event("native_getifaddrs", "filtered");
        if (visible_head != nullptr) {
            remember_hidden_list(visible_head, hidden_head);
        } else if (orig_freeifaddrs != nullptr) {
            orig_freeifaddrs(hidden_head);
        }
    }
    *ifap = visible_head;
    return 0;
}

static int proxy_getifaddrs(ifaddrs **ifap) {
    if (orig_getifaddrs == nullptr) return -1;
    int result = orig_getifaddrs(ifap);
    if (result == 0) {
        filter_ifaddrs(ifap);
    }
    return result;
}

static void proxy_freeifaddrs(ifaddrs *ifap) {
    if (orig_freeifaddrs == nullptr) return;
    ifaddrs *hidden_head = take_hidden_list(ifap);
    if (ifap != nullptr) orig_freeifaddrs(ifap);
    if (hidden_head != nullptr) orig_freeifaddrs(hidden_head);
}

// ---------------------------------------------------------------------------
// if_* small helpers
// ---------------------------------------------------------------------------

static unsigned int proxy_if_nametoindex(const char *ifname) {
    if (is_tunnel_interface_or_alias(ifname)) {
        report_native_event("native_if_nametoindex", ifname);
        return 0;
    }
    return orig_if_nametoindex != nullptr ? orig_if_nametoindex(ifname) : 0;
}

static char *proxy_if_indextoname(unsigned int ifindex, char *ifname) {
    char *result = orig_if_indextoname != nullptr ? orig_if_indextoname(ifindex, ifname) : nullptr;
    if (is_tunnel_interface_or_alias(result)) {
        report_native_event("native_if_indextoname", result);
        return write_fallback_interface_name(ifname);
    }
    return result;
}

static struct if_nameindex *proxy_if_nameindex() {
    struct if_nameindex *result = orig_if_nameindex != nullptr ? orig_if_nameindex() : nullptr;
    if (result == nullptr) return nullptr;

    unsigned int total = 0;
    while (result[total].if_index != 0 || result[total].if_name != nullptr) total++;

    bool filtered = false;
    unsigned int write_index = 0;
    for (unsigned int read_index = 0; read_index < total; read_index++) {
        if (is_tunnel_interface_or_alias(result[read_index].if_name)) {
            report_native_event("native_if_nameindex", result[read_index].if_name);
            filtered = true;
            continue;
        }
        if (write_index != read_index) {
            result[write_index] = result[read_index];
        }
        write_index++;
    }
    if (filtered) {
        result[write_index].if_index = 0;
        result[write_index].if_name = nullptr;
    }
    return result;
}

// ---------------------------------------------------------------------------
// open / openat / fopen / close
// ---------------------------------------------------------------------------

static FILE *proxy_fopen(const char *path, const char *mode) {
    bool is_procfs = false;
    if (should_block_native_path(path, &is_procfs)) {
        int error = blocked_path_errno(is_procfs);
        report_native_event(is_procfs ? "native_procfs_fopen" : "native_sysfs_fopen", path);
        errno = error;
        return nullptr;
    }
    FILE *stream = orig_fopen != nullptr ? orig_fopen(path, mode) : fopen(path, mode);
    if (stream != nullptr && get_procfs_hook_mode() == PROCFS_HOOK_MODE_FILTER) {
        ProcfsKind kind = classify_procfs_path(path);
        if (kind != PROCFS_KIND_NONE) {
            int fd = fileno(stream);
            try_track_procfs_fd(fd, path);
        }
    }
    return stream;
}

static int proxy_open(const char *path, int flags, ...) {
    bool is_procfs = false;
    if (should_block_native_path(path, &is_procfs)) {
        int error = blocked_path_errno(is_procfs);
        report_native_event(is_procfs ? "native_procfs_open" : "native_sysfs_open", path);
        errno = error;
        return -1;
    }
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    int fd = orig_open != nullptr ? orig_open(path, flags, mode) : open(path, flags, mode);
    if (fd >= 0 && get_procfs_hook_mode() == PROCFS_HOOK_MODE_FILTER) {
        try_track_procfs_fd(fd, path);
    }
    return fd;
}

static int proxy_openat(int dirfd, const char *path, int flags, ...) {
    bool is_procfs = false;
    if (should_block_native_path(path, &is_procfs)) {
        int error = blocked_path_errno(is_procfs);
        report_native_event(is_procfs ? "native_procfs_openat" : "native_sysfs_openat", path);
        errno = error;
        return -1;
    }
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    int fd = orig_openat != nullptr ? orig_openat(dirfd, path, flags, mode) : openat(dirfd, path, flags, mode);
    if (fd >= 0 && get_procfs_hook_mode() == PROCFS_HOOK_MODE_FILTER && dirfd == AT_FDCWD) {
        try_track_procfs_fd(fd, path);
    }
    return fd;
}

static int proxy_close(int fd) {
    forget_netlink_fd(fd);
    forget_procfs_fd(fd);
    return orig_close != nullptr ? orig_close(fd) : close(fd);
}

static int proxy_fclose(FILE *stream) {
    if (stream != nullptr) {
        int fd = fileno(stream);
        if (fd >= 0) {
            forget_procfs_fd(fd);
        }
    }
    return orig_fclose != nullptr ? orig_fclose(stream) : fclose(stream);
}

// ---------------------------------------------------------------------------
// ioctl
// ---------------------------------------------------------------------------

static int proxy_ioctl(int fd, unsigned long request, ...) {
    void *argp = nullptr;
    va_list args;
    va_start(args, request);
    argp = va_arg(args, void *);
    va_end(args);

    if (argp != nullptr) {
        ifreq *req = reinterpret_cast<ifreq *>(argp);
        switch (request) {
            case SIOCGIFCONF: {
                int result = orig_ioctl != nullptr ? orig_ioctl(fd, request, argp) : -1;
                if (result != 0) return result;
                ifconf *config = reinterpret_cast<ifconf *>(argp);
                if (config == nullptr || config->ifc_req == nullptr || config->ifc_len <= 0) return result;
                int count = config->ifc_len / static_cast<int>(sizeof(ifreq));
                int visible_count = 0;
                bool filtered = false;
                for (int index = 0; index < count; index++) {
                    ifreq *entry = &config->ifc_req[index];
                    if (is_tunnel_interface_or_alias(entry->ifr_name)) {
                        filtered = true;
                        report_native_event("native_ioctl", entry->ifr_name);
                        continue;
                    }
                    if (visible_count != index) {
                        memcpy(&config->ifc_req[visible_count], entry, sizeof(ifreq));
                    }
                    visible_count++;
                }
                if (filtered) {
                    config->ifc_len = visible_count * static_cast<int>(sizeof(ifreq));
                }
                return result;
            }
            case SIOCGIFMTU: case SIOCGIFINDEX: case SIOCGIFFLAGS: case SIOCGIFADDR:
            case SIOCGIFNETMASK: case SIOCGIFBRDADDR: case SIOCGIFDSTADDR: case SIOCGIFHWADDR:
            case SIOCGIFPFLAGS: case SIOCGIFENCAP: case SIOCGIFTXQLEN: case SIOCGIFMETRIC:
                if (is_tunnel_interface_or_alias(req->ifr_name)) {
                    report_native_event("native_ioctl", req->ifr_name);
                    errno = ENODEV;
                    return -1;
                }
                break;
            case SIOCGIFNAME: {
                int result = orig_ioctl != nullptr ? orig_ioctl(fd, request, argp) : -1;
                if (result == 0 && is_tunnel_interface_or_alias(req->ifr_name)) {
                    report_native_event("native_ioctl", req->ifr_name);
                    write_fallback_interface_name(req->ifr_name);
                    return 0;
                }
                return result;
            }
            default: break;
        }
    }
    return orig_ioctl != nullptr ? orig_ioctl(fd, request, argp) : -1;
}

// ---------------------------------------------------------------------------
// socket tracking
// ---------------------------------------------------------------------------

static int proxy_socket(int domain, int type, int protocol) {
    int result = orig_socket != nullptr ? orig_socket(domain, type, protocol) : -1;
    if (result >= 0 && domain == AF_NETLINK) {
        if (protocol == NETLINK_ROUTE) {
            remember_netlink_fd(result, false);
        } else if (protocol == NETLINK_SOCK_DIAG) {
            remember_netlink_fd(result, true);
        }
    }
    return result;
}

// ---------------------------------------------------------------------------
// Netlink message classification
// ---------------------------------------------------------------------------

static bool netlink_link_message_hidden(const nlmsghdr *header) {
    if (header == nullptr || header->nlmsg_len < NLMSG_LENGTH(sizeof(ifinfomsg))) return false;
    const ifinfomsg *info = reinterpret_cast<const ifinfomsg *>(NLMSG_DATA(header));
    if (is_tun_ifindex_cached(info->ifi_index)) return true;
    int payload_len = static_cast<int>(header->nlmsg_len) - NLMSG_LENGTH(sizeof(ifinfomsg));
    for (const rtattr *attr = IFLA_RTA(info); RTA_OK(attr, payload_len); attr = RTA_NEXT(attr, payload_len)) {
        if (attr->rta_type != IFLA_IFNAME) continue;
        const char *ifname = reinterpret_cast<const char *>(RTA_DATA(attr));
        return is_tunnel_interface_or_alias(ifname);
    }
    return false;
}

static bool netlink_addr_message_hidden(const nlmsghdr *header) {
    if (header == nullptr || header->nlmsg_len < NLMSG_LENGTH(sizeof(ifaddrmsg))) return false;
    const ifaddrmsg *info = reinterpret_cast<const ifaddrmsg *>(NLMSG_DATA(header));
    return is_tun_ifindex_cached(static_cast<int>(info->ifa_index));
}

static bool netlink_route_message_hidden(const nlmsghdr *header) {
    if (header == nullptr || header->nlmsg_len < NLMSG_LENGTH(sizeof(rtmsg))) return false;
    const rtmsg *message = reinterpret_cast<const rtmsg *>(NLMSG_DATA(header));
    int payload_len = static_cast<int>(header->nlmsg_len) - NLMSG_LENGTH(sizeof(rtmsg));
    for (const rtattr *attr = RTM_RTA(message); RTA_OK(attr, payload_len); attr = RTA_NEXT(attr, payload_len)) {
        if (attr->rta_type == RTA_OIF && RTA_PAYLOAD(attr) >= static_cast<int>(sizeof(int))) {
            int ifindex = *reinterpret_cast<const int *>(RTA_DATA(attr));
            if (is_tun_ifindex_cached(ifindex)) return true;
        }
        if (attr->rta_type == RTA_IIF && RTA_PAYLOAD(attr) >= static_cast<int>(sizeof(int))) {
            int ifindex = *reinterpret_cast<const int *>(RTA_DATA(attr));
            if (is_tun_ifindex_cached(ifindex)) return true;
        }
    }
    return false;
}

static bool netlink_link_notification_changes_cache(const nlmsghdr *header) {
    return header != nullptr
            && (header->nlmsg_type == RTM_NEWLINK || header->nlmsg_type == RTM_DELLINK);
}

// ---------------------------------------------------------------------------
// Netlink response filtering (NETLINK_ROUTE)
// ---------------------------------------------------------------------------

static ssize_t filter_netlink_route_messages(void *buffer, size_t length, bool *filtered_any) {
    if (buffer == nullptr || length < sizeof(nlmsghdr)) {
        if (filtered_any != nullptr) *filtered_any = false;
        return static_cast<ssize_t>(length);
    }
    auto *base = reinterpret_cast<unsigned char *>(buffer);
    size_t read_offset = 0;
    size_t write_offset = 0;
    bool filtered = false;
    bool cache_dirty = false;
    uint32_t last_seq = 0;

    while (read_offset + sizeof(nlmsghdr) <= length) {
        nlmsghdr *header = reinterpret_cast<nlmsghdr *>(base + read_offset);
        if (header->nlmsg_len < sizeof(nlmsghdr) || read_offset + header->nlmsg_len > length) {
            break;
        }
        last_seq = header->nlmsg_seq;

        bool hide = false;
        switch (header->nlmsg_type) {
            case RTM_NEWLINK: hide = netlink_link_message_hidden(header); break;
            case RTM_DELLINK: hide = netlink_link_message_hidden(header); cache_dirty = true; break;
            case RTM_NEWADDR: hide = netlink_addr_message_hidden(header); break;
            case RTM_DELADDR: hide = netlink_addr_message_hidden(header); break;
            case RTM_NEWROUTE: hide = netlink_route_message_hidden(header); break;
            case RTM_DELROUTE: hide = netlink_route_message_hidden(header); break;
            default: break;
        }
        if (netlink_link_notification_changes_cache(header)) cache_dirty = true;

        if (hide) {
            filtered = true;
        } else {
            if (write_offset != read_offset) {
                memmove(base + write_offset, header, header->nlmsg_len);
            }
            write_offset += header->nlmsg_len;
        }
        read_offset += NLMSG_ALIGN(header->nlmsg_len);
    }

    if (cache_dirty) invalidate_ifindex_cache();
    if (filtered_any != nullptr) *filtered_any = filtered;
    if (!filtered) return static_cast<ssize_t>(length);

    if (write_offset == 0 && length >= sizeof(nlmsghdr)) {
        auto *done = reinterpret_cast<nlmsghdr *>(base);
        memset(done, 0, sizeof(nlmsghdr));
        done->nlmsg_len = NLMSG_LENGTH(0);
        done->nlmsg_type = NLMSG_DONE;
        done->nlmsg_flags = 0;
        done->nlmsg_seq = last_seq;
        done->nlmsg_pid = 0;
        return static_cast<ssize_t>(done->nlmsg_len);
    }
    return static_cast<ssize_t>(write_offset);
}

// ---------------------------------------------------------------------------
// Netlink response filtering (NETLINK_SOCK_DIAG)
// ---------------------------------------------------------------------------

static ssize_t filter_netlink_sock_diag_messages(void *buffer, size_t length, bool *filtered_any) {
    if (buffer == nullptr || length < sizeof(nlmsghdr)) {
        if (filtered_any != nullptr) *filtered_any = false;
        return static_cast<ssize_t>(length);
    }
    auto *base = reinterpret_cast<unsigned char *>(buffer);
    size_t read_offset = 0;
    size_t write_offset = 0;
    bool filtered = false;
    uint32_t last_seq = 0;

    while (read_offset + sizeof(nlmsghdr) <= length) {
        nlmsghdr *header = reinterpret_cast<nlmsghdr *>(base + read_offset);
        if (header->nlmsg_len < sizeof(nlmsghdr) || read_offset + header->nlmsg_len > length) {
            break;
        }
        last_seq = header->nlmsg_seq;

        bool hide = false;
        if (header->nlmsg_type == SOCK_DIAG_BY_FAMILY
                && header->nlmsg_len >= NLMSG_LENGTH(sizeof(inet_diag_msg))) {
            const inet_diag_msg *diag = reinterpret_cast<const inet_diag_msg *>(NLMSG_DATA(header));
            if (is_tun_ifindex_cached(static_cast<int>(diag->id.idiag_if))) {
                hide = true;
            }
        }

        if (hide) {
            filtered = true;
        } else {
            if (write_offset != read_offset) {
                memmove(base + write_offset, header, header->nlmsg_len);
            }
            write_offset += header->nlmsg_len;
        }
        read_offset += NLMSG_ALIGN(header->nlmsg_len);
    }

    if (filtered_any != nullptr) *filtered_any = filtered;
    if (!filtered) return static_cast<ssize_t>(length);

    if (write_offset == 0 && length >= sizeof(nlmsghdr)) {
        auto *done = reinterpret_cast<nlmsghdr *>(base);
        memset(done, 0, sizeof(nlmsghdr));
        done->nlmsg_len = NLMSG_LENGTH(0);
        done->nlmsg_type = NLMSG_DONE;
        done->nlmsg_flags = 0;
        done->nlmsg_seq = last_seq;
        done->nlmsg_pid = 0;
        return static_cast<ssize_t>(done->nlmsg_len);
    }
    return static_cast<ssize_t>(write_offset);
}

// Filter an iovec array in place, updating each iov_len to its post-filter
// size. Returns the new total byte count.
static ssize_t filter_netlink_route_iovecs(
        struct iovec *iov,
        size_t iov_count,
        ssize_t received,
        const char *vector) {
    if (iov == nullptr || iov_count == 0 || received <= 0) return received;

    ssize_t remaining = received;
    ssize_t total = 0;
    bool filtered_any = false;
    for (size_t index = 0; index < iov_count && remaining > 0; index++) {
        struct iovec *entry = &iov[index];
        if (entry->iov_base == nullptr || entry->iov_len == 0) continue;
        size_t chunk_len = remaining < static_cast<ssize_t>(entry->iov_len)
                ? static_cast<size_t>(remaining)
                : entry->iov_len;
        bool filtered_chunk = false;
        ssize_t filtered_len = filter_netlink_route_messages(entry->iov_base, chunk_len, &filtered_chunk);
        if (filtered_chunk) {
            filtered_any = true;
            entry->iov_len = filtered_len > 0 ? static_cast<size_t>(filtered_len) : 0;
        }
        total += filtered_len;
        remaining -= static_cast<ssize_t>(chunk_len);
    }
    if (filtered_any && vector != nullptr) report_native_event(vector, "filtered");
    return total;
}

static ssize_t filter_netlink_sock_diag_iovecs(
        struct iovec *iov,
        size_t iov_count,
        ssize_t received,
        const char *vector) {
    if (iov == nullptr || iov_count == 0 || received <= 0) return received;

    ssize_t remaining = received;
    ssize_t total = 0;
    bool filtered_any = false;
    for (size_t index = 0; index < iov_count && remaining > 0; index++) {
        struct iovec *entry = &iov[index];
        if (entry->iov_base == nullptr || entry->iov_len == 0) continue;
        size_t chunk_len = remaining < static_cast<ssize_t>(entry->iov_len)
                ? static_cast<size_t>(remaining)
                : entry->iov_len;
        bool filtered_chunk = false;
        ssize_t filtered_len = filter_netlink_sock_diag_messages(entry->iov_base, chunk_len, &filtered_chunk);
        if (filtered_chunk) {
            filtered_any = true;
            entry->iov_len = filtered_len > 0 ? static_cast<size_t>(filtered_len) : 0;
        }
        total += filtered_len;
        remaining -= static_cast<ssize_t>(chunk_len);
    }
    if (filtered_any && vector != nullptr) report_native_event(vector, "filtered");
    return total;
}

// ---------------------------------------------------------------------------
// read / recv / recvfrom / recvmsg / recvmmsg
// ---------------------------------------------------------------------------

static ssize_t handle_read_like(int fd, void *buf, ssize_t result, const char *netlink_vector) {
    if (result <= 0 || buf == nullptr) return result;
    if (is_netlink_route_fd(fd)) {
        struct iovec iov = {buf, static_cast<size_t>(result)};
        return filter_netlink_route_iovecs(&iov, 1, result, netlink_vector);
    }
    if (is_netlink_sock_diag_fd(fd)) {
        struct iovec iov = {buf, static_cast<size_t>(result)};
        return filter_netlink_sock_diag_iovecs(&iov, 1, result, "native_netlink_sock_diag");
    }
    return result;
}

static ssize_t proxy_read(int fd, void *buf, size_t count) {
    ssize_t served = procfs_serve_read(fd, buf, count);
    if (served != -2) return served;
    ssize_t result = orig_read != nullptr ? orig_read(fd, buf, count) : -1;
    return handle_read_like(fd, buf, result, "native_netlink_read");
}

static ssize_t proxy___read_chk(int fd, void *buf, size_t count, size_t buf_size) {
    ssize_t served = procfs_serve_read(fd, buf, count);
    if (served != -2) return served;
    ssize_t result = orig___read_chk != nullptr ? orig___read_chk(fd, buf, count, buf_size) : -1;
    return handle_read_like(fd, buf, result, "native_netlink_read");
}

static ssize_t proxy_recv(int sockfd, void *buf, size_t len, int flags) {
    ssize_t result = orig_recv != nullptr ? orig_recv(sockfd, buf, len, flags) : -1;
    return handle_read_like(sockfd, buf, result, "native_netlink_recv");
}

static ssize_t proxy_recvfrom(int sockfd, void *buf, size_t len, int flags, struct sockaddr *src_addr, socklen_t *addrlen) {
    ssize_t result = orig_recvfrom != nullptr ? orig_recvfrom(sockfd, buf, len, flags, src_addr, addrlen) : -1;
    return handle_read_like(sockfd, buf, result, "native_netlink_recvfrom");
}

static ssize_t proxy___recvfrom_chk(int sockfd, void *buf, size_t len, size_t buf_size,
                                    int flags, struct sockaddr *src_addr, socklen_t *addrlen) {
    ssize_t result = orig___recvfrom_chk != nullptr
            ? orig___recvfrom_chk(sockfd, buf, len, buf_size, flags, src_addr, addrlen)
            : -1;
    return handle_read_like(sockfd, buf, result, "native_netlink_recvfrom");
}

static ssize_t proxy_recvmsg(int sockfd, struct msghdr *msg, int flags) {
    ssize_t result = orig_recvmsg != nullptr ? orig_recvmsg(sockfd, msg, flags) : -1;
    if (result <= 0 || msg == nullptr || msg->msg_iov == nullptr) return result;
    if (is_netlink_route_fd(sockfd)) {
        return filter_netlink_route_iovecs(msg->msg_iov, msg->msg_iovlen, result, "native_netlink_recvmsg");
    }
    if (is_netlink_sock_diag_fd(sockfd)) {
        return filter_netlink_sock_diag_iovecs(msg->msg_iov, msg->msg_iovlen, result, "native_netlink_sock_diag");
    }
    return result;
}

static int proxy_recvmmsg(int sockfd, struct mmsghdr *vmessages, unsigned int vlen, int flags, struct timespec *timeout) {
    int result = orig_recvmmsg != nullptr ? orig_recvmmsg(sockfd, vmessages, vlen, flags, timeout) : -1;
    if (result <= 0 || vmessages == nullptr) return result;
    bool route = is_netlink_route_fd(sockfd);
    bool sdiag = !route && is_netlink_sock_diag_fd(sockfd);
    if (!route && !sdiag) return result;

    bool any_filtered = false;
    for (int index = 0; index < result; index++) {
        struct mmsghdr *message = &vmessages[index];
        ssize_t filtered_len = route
                ? filter_netlink_route_iovecs(message->msg_hdr.msg_iov,
                                              message->msg_hdr.msg_iovlen,
                                              static_cast<ssize_t>(message->msg_len),
                                              "native_netlink_recvmmsg")
                : filter_netlink_sock_diag_iovecs(message->msg_hdr.msg_iov,
                                                  message->msg_hdr.msg_iovlen,
                                                  static_cast<ssize_t>(message->msg_len),
                                                  "native_netlink_sock_diag");
        if (filtered_len != static_cast<ssize_t>(message->msg_len)) {
            any_filtered = true;
            message->msg_len = static_cast<unsigned int>(filtered_len > 0 ? filtered_len : 0);
        }
    }
    if (any_filtered) report_native_event(route ? "native_netlink_recvmmsg" : "native_netlink_sock_diag", "filtered");
    return result;
}

// ---------------------------------------------------------------------------
// Directory listing hooks
// ---------------------------------------------------------------------------

static DIR *proxy_opendir(const char *name) {
    DIR *dir = orig_opendir != nullptr ? orig_opendir(name) : opendir(name);
    if (dir != nullptr && name != nullptr
            && (strcmp(name, kSysClassNetDir) == 0 || strcmp(name, "/sys/class/net/") == 0)) {
        remember_dir_path(dir, kSysClassNetDir);
    }
    return dir;
}

static int proxy_closedir(DIR *dir) {
    take_dir_path(dir);
    return orig_closedir != nullptr ? orig_closedir(dir) : closedir(dir);
}

static struct dirent *proxy_readdir(DIR *dir) {
    if (!dir_path_is_sysfs_net(dir)) {
        return orig_readdir != nullptr ? orig_readdir(dir) : readdir(dir);
    }
    for (;;) {
        struct dirent *entry = orig_readdir != nullptr ? orig_readdir(dir) : readdir(dir);
        if (entry == nullptr) return nullptr;
        if (is_tunnel_interface(entry->d_name)) {
            report_native_event("native_sysfs_readdir", entry->d_name);
            continue;
        }
        return entry;
    }
}

static struct dirent64 *proxy_readdir64(DIR *dir) {
    if (!dir_path_is_sysfs_net(dir)) {
        return orig_readdir64 != nullptr ? orig_readdir64(dir) : readdir64(dir);
    }
    for (;;) {
        struct dirent64 *entry = orig_readdir64 != nullptr ? orig_readdir64(dir) : readdir64(dir);
        if (entry == nullptr) return nullptr;
        if (is_tunnel_interface(entry->d_name)) {
            report_native_event("native_sysfs_readdir", entry->d_name);
            continue;
        }
        return entry;
    }
}

// ---------------------------------------------------------------------------
// access / stat family
// ---------------------------------------------------------------------------

static bool path_requires_hiding(const char *path) {
    if (path == nullptr) return false;
    if (is_tunnel_sysfs_path(path)) return true;
    if (is_tun_device_path(path)) return true;
    return false;
}

static int proxy_access(const char *path, int mode) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_access", path);
        errno = ENOENT;
        return -1;
    }
    return orig_access != nullptr ? orig_access(path, mode) : access(path, mode);
}

static int proxy_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_access", path);
        errno = ENOENT;
        return -1;
    }
    return orig_faccessat != nullptr ? orig_faccessat(dirfd, path, mode, flags) : faccessat(dirfd, path, mode, flags);
}

static int proxy_stat(const char *path, struct stat *buf) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_stat", path);
        errno = ENOENT;
        return -1;
    }
    return orig_stat != nullptr ? orig_stat(path, buf) : stat(path, buf);
}

static int proxy_lstat(const char *path, struct stat *buf) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_stat", path);
        errno = ENOENT;
        return -1;
    }
    return orig_lstat != nullptr ? orig_lstat(path, buf) : lstat(path, buf);
}

static int proxy_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_stat", path);
        errno = ENOENT;
        return -1;
    }
    return orig_fstatat != nullptr ? orig_fstatat(dirfd, path, buf, flags) : fstatat(dirfd, path, buf, flags);
}

static int proxy_statfs(const char *path, struct statfs *buf) {
    if (path_requires_hiding(path)) {
        report_native_event("native_sysfs_stat", path);
        errno = ENOENT;
        return -1;
    }
    return orig_statfs != nullptr ? orig_statfs(path, buf) : statfs(path, buf);
}

// ---------------------------------------------------------------------------
// Hook install
// ---------------------------------------------------------------------------

static int refresh_hooks() {
    int refresh_result = xhook_refresh(0);
    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "xhook refresh=%d getifaddrs=%p if_nameindex=%p",
            refresh_result,
            reinterpret_cast<void *>(orig_getifaddrs),
            reinterpret_cast<void *>(orig_if_nameindex));
    return refresh_result;
}

static void register_hook(const char *symbol, void *proxy, void **orig, bool critical, int &critical_failures) {
    int result = xhook_register(kHookedLibrariesPattern, symbol, proxy, orig);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "xhook_register(%s) failed=%d critical=%d", symbol, result, critical ? 1 : 0);
        if (critical) critical_failures++;
    }
}

static bool g_shadowhook_ok = false;

static bool inline_hook_libc(const char *symbol, void *proxy, void **orig) {
#ifdef WINGS_HAS_SHADOWHOOK
    if (!g_shadowhook_ok) return false;
    void *stub = shadowhook_hook_sym_name("libc.so", symbol, proxy, orig);
    if (stub != nullptr) return true;
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "shadowhook_hook_sym_name(libc.so, %s) failed errno=%d (%s)",
                        symbol, shadowhook_get_errno(),
                        shadowhook_to_errmsg(shadowhook_get_errno()));
    return false;
#else
    (void) symbol; (void) proxy; (void) orig;
    return false;
#endif
}

static void register_hook_prefer_inline(
        const char *symbol, void *proxy, void **orig, bool critical, int &critical_failures) {
    if (inline_hook_libc(symbol, proxy, orig)) {
        return;
    }
    int result = xhook_register(kHookedLibrariesPattern, symbol, proxy, orig);
    if (result != 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "hook(%s) failed inline+plt critical=%d", symbol, critical ? 1 : 0);
        if (critical) critical_failures++;
    }
}

static bool user_enabled_inline_hooks() {
    char value[PROP_VALUE_MAX] = {0};
    int length = __system_property_get(kInlineHooksProp, value);
    if (length <= 0) return false;
    return strcmp(value, "1") == 0;
}

static void init_shadowhook() {
#ifdef WINGS_HAS_SHADOWHOOK
    if (!user_enabled_inline_hooks()) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "inline hooks disabled by user, skipping shadowhook init");
        return;
    }
    int rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    g_shadowhook_ok = (rc == SHADOWHOOK_ERRNO_OK);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "shadowhook_init rc=%d ok=%d version=%s",
                        rc, g_shadowhook_ok ? 1 : 0, shadowhook_get_version());
#endif
}

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_wings_v_xposed_NativeVpnDetectionHook_nativeInstall(JNIEnv *env, jclass) {
    if (g_installed) return JNI_TRUE;

    if (is_critical_infrastructure_process()) {
        __android_log_print(
                ANDROID_LOG_INFO, LOG_TAG,
                "Skipping native hooks for critical process %s",
                get_current_process_name().c_str());
        return JNI_FALSE;
    }

    if (g_reporter_class == nullptr || g_report_method == nullptr) {
        jclass local_reporter_class = env->FindClass("wings/v/xposed/XposedAttackReporter");
        if (local_reporter_class != nullptr) {
            g_reporter_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_reporter_class));
            env->DeleteLocalRef(local_reporter_class);
        }
        if (g_reporter_class != nullptr) {
            g_report_method = env->GetStaticMethodID(
                    g_reporter_class, "reportNativeEvent",
                    "(Ljava/lang/String;Ljava/lang/String;)V");
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    xhook_enable_debug(0);
    xhook_enable_sigsegv_protection(1);

    init_shadowhook();

    static const char *const kIgnoredLibraryPatterns[] = {
            "libwingsxposednative\\.so$",
            ".*/vulkan\\..*\\.so$",
            ".*/libvulkan\\.so$",
            ".*/libEGL.*\\.so$",
            ".*/libGLES.*\\.so$",
            ".*/libOpenCL.*\\.so$",
            ".*/libhwui\\.so$",
            ".*/libgui\\.so$",
            ".*/libandroid_runtime\\.so$",
            ".*/gralloc\\..*\\.so$",
            ".*/hwcomposer\\..*\\.so$",
    };
    for (const char *pattern : kIgnoredLibraryPatterns) {
        xhook_ignore(pattern, nullptr);
    }

    int critical_failures = 0;

    register_hook_prefer_inline("getifaddrs", reinterpret_cast<void *>(proxy_getifaddrs),
                  reinterpret_cast<void **>(&orig_getifaddrs), true, critical_failures);
    register_hook_prefer_inline("freeifaddrs", reinterpret_cast<void *>(proxy_freeifaddrs),
                  reinterpret_cast<void **>(&orig_freeifaddrs), true, critical_failures);
    register_hook_prefer_inline("if_nametoindex", reinterpret_cast<void *>(proxy_if_nametoindex),
                  reinterpret_cast<void **>(&orig_if_nametoindex), true, critical_failures);
    register_hook_prefer_inline("if_indextoname", reinterpret_cast<void *>(proxy_if_indextoname),
                  reinterpret_cast<void **>(&orig_if_indextoname), true, critical_failures);
    register_hook_prefer_inline("ioctl", reinterpret_cast<void *>(proxy_ioctl),
                  reinterpret_cast<void **>(&orig_ioctl), true, critical_failures);

    register_hook("open", reinterpret_cast<void *>(proxy_open),
                  reinterpret_cast<void **>(&orig_open), true, critical_failures);
    register_hook("openat", reinterpret_cast<void *>(proxy_openat),
                  reinterpret_cast<void **>(&orig_openat), true, critical_failures);
    register_hook("close", reinterpret_cast<void *>(proxy_close),
                  reinterpret_cast<void **>(&orig_close), true, critical_failures);
    register_hook("read", reinterpret_cast<void *>(proxy_read),
                  reinterpret_cast<void **>(&orig_read), true, critical_failures);
    register_hook("socket", reinterpret_cast<void *>(proxy_socket),
                  reinterpret_cast<void **>(&orig_socket), true, critical_failures);

    register_hook_prefer_inline("if_nameindex", reinterpret_cast<void *>(proxy_if_nameindex),
                  reinterpret_cast<void **>(&orig_if_nameindex), false, critical_failures);
    register_hook("fopen", reinterpret_cast<void *>(proxy_fopen),
                  reinterpret_cast<void **>(&orig_fopen), false, critical_failures);
    register_hook("fclose", reinterpret_cast<void *>(proxy_fclose),
                  reinterpret_cast<void **>(&orig_fclose), false, critical_failures);
    register_hook("__read_chk", reinterpret_cast<void *>(proxy___read_chk),
                  reinterpret_cast<void **>(&orig___read_chk), false, critical_failures);
    register_hook("recv", reinterpret_cast<void *>(proxy_recv),
                  reinterpret_cast<void **>(&orig_recv), false, critical_failures);
    register_hook("recvfrom", reinterpret_cast<void *>(proxy_recvfrom),
                  reinterpret_cast<void **>(&orig_recvfrom), false, critical_failures);
    register_hook("__recvfrom_chk", reinterpret_cast<void *>(proxy___recvfrom_chk),
                  reinterpret_cast<void **>(&orig___recvfrom_chk), false, critical_failures);
    register_hook("recvmsg", reinterpret_cast<void *>(proxy_recvmsg),
                  reinterpret_cast<void **>(&orig_recvmsg), false, critical_failures);
    register_hook("recvmmsg", reinterpret_cast<void *>(proxy_recvmmsg),
                  reinterpret_cast<void **>(&orig_recvmmsg), false, critical_failures);
    register_hook_prefer_inline("opendir", reinterpret_cast<void *>(proxy_opendir),
                  reinterpret_cast<void **>(&orig_opendir), false, critical_failures);
    register_hook_prefer_inline("closedir", reinterpret_cast<void *>(proxy_closedir),
                  reinterpret_cast<void **>(&orig_closedir), false, critical_failures);
    register_hook_prefer_inline("readdir", reinterpret_cast<void *>(proxy_readdir),
                  reinterpret_cast<void **>(&orig_readdir), false, critical_failures);
    register_hook_prefer_inline("readdir64", reinterpret_cast<void *>(proxy_readdir64),
                  reinterpret_cast<void **>(&orig_readdir64), false, critical_failures);
    register_hook_prefer_inline("access", reinterpret_cast<void *>(proxy_access),
                  reinterpret_cast<void **>(&orig_access), false, critical_failures);
    register_hook_prefer_inline("faccessat", reinterpret_cast<void *>(proxy_faccessat),
                  reinterpret_cast<void **>(&orig_faccessat), false, critical_failures);
    register_hook_prefer_inline("stat", reinterpret_cast<void *>(proxy_stat),
                  reinterpret_cast<void **>(&orig_stat), false, critical_failures);
    register_hook_prefer_inline("lstat", reinterpret_cast<void *>(proxy_lstat),
                  reinterpret_cast<void **>(&orig_lstat), false, critical_failures);
    register_hook_prefer_inline("fstatat", reinterpret_cast<void *>(proxy_fstatat),
                  reinterpret_cast<void **>(&orig_fstatat), false, critical_failures);
    register_hook_prefer_inline("statfs", reinterpret_cast<void *>(proxy_statfs),
                  reinterpret_cast<void **>(&orig_statfs), false, critical_failures);

    int refresh_result = refresh_hooks();

    __android_log_print(
            ANDROID_LOG_INFO, LOG_TAG,
            "native hook installed critical_failures=%d refresh=%d pattern=%s",
            critical_failures, refresh_result, kHookedLibrariesPattern);

    g_installed = critical_failures == 0;
    return g_installed ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_wings_v_xposed_NativeVpnDetectionHook_nativeRefresh(JNIEnv *, jclass) {
    if (!g_installed) return;
    refresh_hooks();
    invalidate_ifindex_cache();
}
