cmake_minimum_required(VERSION 3.22.1)
project(b0bvr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Khronos 官方 Android OpenXR loader（AAR 里的 prefab 包）。
# PICO OS ≥ 5.9 起支持这条标准路线（本机实测 5.13.7），
# 见 https://developer.picoxr.com/zh/blog/muz6s63x/
# 包名与目标名以 AAR 自带元数据为准（prefab/prefab.json 的 name 是 OpenXR，
# 目标 OpenXR::openxr_loader；写成 openxr_loader 会报
# 「Could not find a package configuration file provided by openxr_loader」——run 88 实测）。
find_package(OpenXR REQUIRED CONFIG)

add_library(b0bvr SHARED
        native_bridge.cpp
        openxr_renderer.cpp
)

target_link_libraries(b0bvr
        OpenXR::openxr_loader
        EGL
        GLESv3
        android
        log
)
