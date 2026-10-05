cmake_minimum_required(VERSION 3.22.1)
project(b0bvr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Khronos 官方 Android OpenXR loader（AAR 里的 prefab 包）。
# PICO OS ≥ 5.9 起支持这条标准路线（本机实测 5.13.7），
# 见 https://developer.picoxr.com/zh/blog/muz6s63x/
find_package(openxr_loader REQUIRED CONFIG)

add_library(b0bvr SHARED
        native_bridge.cpp
)

target_link_libraries(b0bvr
        openxr_loader::openxr_loader
        EGL
        GLESv3
        android
        log
)
