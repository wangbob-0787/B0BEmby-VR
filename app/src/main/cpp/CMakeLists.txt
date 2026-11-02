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

# ncnn 深度推理（2026-11-02 接入）：预编译静态库，只带 arm64-v8a。
# 模型跑在 Vulkan 计算上，和 TFLite 的 GPU delegate 是两条独立的路子。
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/libncnn.a)
foreach(lib glslang glslang-default-resource-limits MachineIndependent GenericCodeGen OSDependent SPIRV)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/lib${lib}.a)
endforeach()

add_library(b0bvr SHARED
        native_bridge.cpp
        openxr_renderer.cpp
        depth_ncnn.cpp
)

target_include_directories(b0bvr PRIVATE ${NCNN_DIR}/include)

# ncnn 的安卓预编译库带 OpenMP（符号 __kmpc_*），链接时必须带上 NDK 自带的运行时，
# 否则报一堆 undefined symbol。编译和链接都要加，缺一边都不行。
target_compile_options(b0bvr PRIVATE -fopenmp)
target_link_options(b0bvr PRIVATE -fopenmp)

target_link_libraries(b0bvr
        OpenXR::openxr_loader
        ncnn
        glslang
        glslang-default-resource-limits
        MachineIndependent
        GenericCodeGen
        OSDependent
        SPIRV
        vulkan
        z
        EGL
        GLESv3
        android
        log
)
