#include <errno.h>
#include <unistd.h>
#include <linux/bpf.h>
#include <linux/unistd.h>
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "WINGSV-RootJni"
#define ALOG(priority, tag, fmt, ...) \
    __android_log_print(ANDROID_##priority, tag, fmt, ##__VA_ARGS__)
#define ALOGE(...) ((void)ALOG(LOG_ERROR, LOG_TAG, __VA_ARGS__))

static int ThrowException(JNIEnv *env, const char *className, const char *ctorSig, ...) {
    int status = -1;
    jclass exceptionClass = nullptr;

    va_list args;
    va_start(args, ctorSig);

    {
        exceptionClass = env->FindClass(className);
        if (exceptionClass == nullptr) {
            ALOGE("Unable to find exception class %s", className);
            goto end;
        }

        jmethodID init = env->GetMethodID(exceptionClass, "<init>", ctorSig);
        if (init == nullptr) {
            ALOGE("Failed to find constructor for %s %s", className, ctorSig);
            goto end;
        }

        jobject instance = env->NewObjectV(exceptionClass, init, args);
        if (instance == nullptr) {
            ALOGE("Failed to construct %s", className);
            goto end;
        }

        if (env->Throw((jthrowable) instance) != JNI_OK) {
            ALOGE("Failed to throw %s", className);
            goto end;
        }

        status = 0;
    }

    end:
    va_end(args);
    if (exceptionClass != nullptr) {
        env->DeleteLocalRef(exceptionClass);
    }
    return status;
}

#define THROW_EXCEPTION_WITH_MESSAGE(env, className, ctorSig, msg, ...) ({                      \
    jstring _detailMessage = env->NewStringUTF(msg);                                            \
    int _status = ThrowException(env, className, ctorSig, _detailMessage, ##__VA_ARGS__);      \
    if (_detailMessage != nullptr) {                                                             \
        env->DeleteLocalRef(_detailMessage);                                                     \
    }                                                                                            \
    _status;                                                                                     \
})

static int jniThrowErrnoException(JNIEnv *env, const char *functionName, int errnoValue) {
    return THROW_EXCEPTION_WITH_MESSAGE(env, "android/system/ErrnoException",
            "(Ljava/lang/String;I)V", functionName, errnoValue);
}

struct android_net_UidOwnerValue {
    [[maybe_unused]] int32_t iif;
    uint32_t rule;
};

static inline long bpf_call(enum bpf_cmd cmd, const bpf_attr &attr) {
    return syscall(__NR_bpf, cmd, &attr, sizeof(attr));
}

static long map_fd = -1L;

extern "C" JNIEXPORT jboolean JNICALL
Java_wings_v_root_server_WingsJni_removeUidInterfaceRules(
        JNIEnv *env,
        [[maybe_unused]] jobject thiz,
        jstring path,
        jint uid,
        jlong rules) {
    if (map_fd < 0) {
        const char *pathname = env->GetStringUTFChars(path, nullptr);
        map_fd = bpf_call(BPF_OBJ_GET, {
                .pathname = (uint64_t) pathname,
        });
        env->ReleaseStringUTFChars(path, pathname);
        if (map_fd < 0) {
            int err = errno;
            if (err != ENOSYS) {
                jniThrowErrnoException(env, "BPF_OBJ_GET", err);
            }
            return false;
        }
    }

    android_net_UidOwnerValue value;
    if (bpf_call(BPF_MAP_LOOKUP_ELEM, {
            .map_fd = (uint32_t) map_fd,
            .key = (uint64_t) &uid,
            .value = (uint64_t) &value,
    })) {
        if (errno != ENOENT) {
            jniThrowErrnoException(env, "BPF_MAP_LOOKUP_ELEM", errno);
        }
        return false;
    }

    if (!(value.rule & rules)) {
        return false;
    }

    value.rule &= ~rules;
    if (value.rule) {
        if (bpf_call(BPF_MAP_UPDATE_ELEM, {
                .map_fd = (uint32_t) map_fd,
                .key = (uint64_t) &uid,
                .value = (uint64_t) &value,
                .flags = BPF_ANY,
        })) {
            jniThrowErrnoException(env, "BPF_MAP_UPDATE_ELEM", errno);
        }
    } else if (bpf_call(BPF_MAP_DELETE_ELEM, {
            .map_fd = (uint32_t) map_fd,
            .key = (uint64_t) &uid,
    })) {
        int err = errno;
        if (err != ENOENT) {
            jniThrowErrnoException(env, "BPF_MAP_DELETE_ELEM", err);
        }
    }
    return true;
}
