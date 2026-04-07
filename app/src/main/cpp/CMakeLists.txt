cmake_minimum_required(VERSION 3.22.1)

project(wings_xposed_native)

find_package(xhook REQUIRED CONFIG)

add_library(wingsxposednative SHARED
        wings_xposed_native.cpp)

target_link_libraries(wingsxposednative
        android
        log
        xhook::xhook
)
