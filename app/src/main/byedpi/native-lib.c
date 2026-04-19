#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <getopt.h>
#include <jni.h>
#include <sys/socket.h>

#include "error.h"
#include "main.h"

extern int server_fd;

static int g_proxy_running = 0;

struct params default_params = {
        .await_int = 10,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
                .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
                .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

static void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_wings_v_byedpi_ByeDpiNative_jniStartProxy(JNIEnv *env,
                                               __attribute__((unused)) jobject thiz,
                                               jobjectArray args) {
    if (g_proxy_running) {
        LOG(LOG_S, "proxy already running");
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t) argc, sizeof(char *));
    if (!argv) {
        LOG(LOG_S, "failed to allocate argv");
        return -1;
    }

    for (int index = 0; index < argc; index++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, index);
        if (!arg) {
            argv[index] = NULL;
            continue;
        }
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[index] = arg_str ? strdup(arg_str) : NULL;
        if (arg_str) {
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        }
        (*env)->DeleteLocalRef(env, arg);
    }

    reset_params();
    g_proxy_running = 1;
    optind = 1;

    LOG(LOG_S, "starting byedpi proxy with %d args", argc);
    int result = main(argc, argv);
    LOG(LOG_S, "byedpi proxy returned %d", result);

    g_proxy_running = 0;
    for (int index = 0; index < argc; index++) {
        free(argv[index]);
    }
    free(argv);
    return result;
}

JNIEXPORT jint JNICALL
Java_wings_v_byedpi_ByeDpiNative_jniStopProxy(__attribute__((unused)) JNIEnv *env,
                                              __attribute__((unused)) jobject thiz) {
    if (!g_proxy_running) {
        LOG(LOG_S, "proxy is not running");
        return -1;
    }
    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_wings_v_byedpi_ByeDpiNative_jniForceClose(__attribute__((unused)) JNIEnv *env,
                                               __attribute__((unused)) jobject thiz) {
    if (close(server_fd) == -1) {
        LOG(LOG_S, "failed to close server socket fd=%d", server_fd);
        return -1;
    }
    g_proxy_running = 0;
    return 0;
}
