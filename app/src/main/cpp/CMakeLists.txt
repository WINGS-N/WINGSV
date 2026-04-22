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
