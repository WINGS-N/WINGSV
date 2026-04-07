LOCAL_PATH := $(call my-dir)
BYEDPI_ROOT := $(LOCAL_PATH)/../../../../external/byedpi

include $(CLEAR_VARS)

LOCAL_MODULE := byedpi
LOCAL_SRC_FILES := \
    native-lib.c \
    ../../../../external/byedpi/packets.c \
    ../../../../external/byedpi/main.c \
    ../../../../external/byedpi/conev.c \
    ../../../../external/byedpi/proxy.c \
    ../../../../external/byedpi/desync.c \
    ../../../../external/byedpi/mpool.c \
    ../../../../external/byedpi/extend.c
LOCAL_C_INCLUDES := $(BYEDPI_ROOT)
LOCAL_CFLAGS := -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic -DANDROID_APP
LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
