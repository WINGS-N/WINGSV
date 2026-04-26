cmake_minimum_required(VERSION 3.22.1)

project(wings_native)

find_package(xhook REQUIRED CONFIG)

add_library(wingsxposednative SHARED
        wings_xposed_native.cpp)

target_link_libraries(wingsxposednative
        android
        log
        xhook::xhook
)

if(ANDROID_ABI MATCHES "^(arm64-v8a|armeabi-v7a)$")
    find_package(shadowhook REQUIRED CONFIG)
    target_link_libraries(wingsxposednative shadowhook::shadowhook)
    target_compile_definitions(wingsxposednative PRIVATE WINGS_HAS_SHADOWHOOK=1)
endif()

set(BYEDPI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/byedpi")
set(BYEDPI_BRIDGE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../byedpi")

add_library(byedpi SHARED
        "${BYEDPI_BRIDGE_ROOT}/native-lib.c"
        "${BYEDPI_ROOT}/packets.c"
        "${BYEDPI_ROOT}/main.c"
        "${BYEDPI_ROOT}/conev.c"
        "${BYEDPI_ROOT}/proxy.c"
        "${BYEDPI_ROOT}/desync.c"
        "${BYEDPI_ROOT}/mpool.c"
        "${BYEDPI_ROOT}/extend.c")

target_include_directories(byedpi PRIVATE
        "${BYEDPI_ROOT}"
        "${BYEDPI_BRIDGE_ROOT}")

target_compile_options(byedpi PRIVATE
        -std=c99
        -O2
        -Wall
        -Wno-unused
        -Wextra
        -Wno-unused-parameter
        -pedantic
        -DANDROID_APP)

target_link_libraries(byedpi
        android
        log)

set(ZSTDJNI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../external/zstd-jni")
file(STRINGS "${ZSTDJNI_ROOT}/version" ZSTDJNI_VERSION)
file(GLOB_RECURSE ZSTDJNI_SOURCES
        "${ZSTDJNI_ROOT}/src/main/native/*.c"
        "${ZSTDJNI_ROOT}/src/main/native/*.S")
list(FILTER ZSTDJNI_SOURCES EXCLUDE REGEX "/legacy/")

add_library(zstd-jni-${ZSTDJNI_VERSION} SHARED ${ZSTDJNI_SOURCES})

target_include_directories(zstd-jni-${ZSTDJNI_VERSION} PRIVATE
        "${ZSTDJNI_ROOT}/src/main/native"
        "${ZSTDJNI_ROOT}/src/main/native/common"
        "${ZSTDJNI_ROOT}/src/main/native/compress"
        "${ZSTDJNI_ROOT}/src/main/native/decompress"
        "${ZSTDJNI_ROOT}/src/main/native/dictBuilder"
        "${ZSTDJNI_ROOT}/src/main/native/legacy")

target_compile_definitions(zstd-jni-${ZSTDJNI_VERSION} PRIVATE
        ZSTD_MULTITHREAD=1
        ZSTD_LEGACY_SUPPORT=0)

target_compile_options(zstd-jni-${ZSTDJNI_VERSION} PRIVATE
        -Oz
        -Wno-unused
        -Wno-unused-function
        -Wno-unused-parameter
        -Wno-unused-variable)

target_link_options(zstd-jni-${ZSTDJNI_VERSION} PRIVATE
        "-Wl,-z,max-page-size=16384")

target_link_libraries(zstd-jni-${ZSTDJNI_VERSION} log)
