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

# 自带音频解码器：独立 target，不并进 b0bvr。
# 理由：库是静态的、版本由 Release ffmpeg-audio-n7.1 钉住；混进主库后一旦符号冲突
# 要排查两套代码，spike 阶段分开编能一眼看出是哪一层的问题。
# 预编译库由 CI 现取（build.yml 的「下载音频解码库」）；本地没有就跳过，不阻断主库编译。
set(FFAUDIO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg-audio)
if(EXISTS ${FFAUDIO_DIR}/lib/arm64-v8a/libavcodec.a)
    add_library(audiodec SHARED audio_decoder_jni.cpp)
    target_include_directories(audiodec PRIVATE ${FFAUDIO_DIR}/include)
    target_link_libraries(audiodec
            ${FFAUDIO_DIR}/lib/arm64-v8a/libavcodec.a
            ${FFAUDIO_DIR}/lib/arm64-v8a/libswresample.a
            ${FFAUDIO_DIR}/lib/arm64-v8a/libavutil.a
            log
            m
            z
            dl
    )
    # 只导出 JNI 入口，其余符号隐藏，避免和主库/系统库撞名。
    target_compile_options(audiodec PRIVATE -fvisibility=hidden)
else()
    message(WARNING "缺 ${FFAUDIO_DIR}/lib/arm64-v8a/libavcodec.a，跳过 audiodec（CI 会现取）")
endif()
