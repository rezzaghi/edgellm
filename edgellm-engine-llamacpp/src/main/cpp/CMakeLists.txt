cmake_minimum_required(VERSION 3.22)
project(edgellm_llamacpp CXX)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(LLAMA_DIR ${THIRD_PARTY}/llama.cpp)

# The NDK ships only the C Vulkan header; ggml-vulkan needs the C++
# bindings (vulkan/vulkan.hpp) from Khronos' Vulkan-Headers.
include_directories(BEFORE SYSTEM
        ${THIRD_PARTY}/Vulkan-Headers/include
        ${THIRD_PARTY}/SPIRV-Headers/include)

# ggml-vulkan does find_package(SPIRV-Headers CONFIG REQUIRED); satisfy it
# from our submodule instead of requiring an installed Vulkan SDK.
include(FetchContent)
FetchContent_Declare(SPIRV-Headers
        SOURCE_DIR ${THIRD_PARTY}/SPIRV-Headers
        OVERRIDE_FIND_PACKAGE)
FetchContent_MakeAvailable(SPIRV-Headers)

# Build llama.cpp statically into our single JNI .so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# Vulkan backend OFF for now: the Adreno 740 driver rejects ggml's compute
# shaders at pipeline creation (see project notes). Revisit for Mali devices
# when Backend.Auto routing lands — the build support below stays wired.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# GPU backend via OpenCL (Qualcomm's contributed backend, tuned for Adreno).
set(GGML_OPENCL ON CACHE BOOL "" FORCE)

# Android vendor CL drivers lack cl_khr_icd, so the Khronos ICD loader can't
# see them; opencl_stub.cpp forwards the API to the vendor driver instead.
add_library(opencl_stub STATIC opencl_stub.cpp)
target_include_directories(opencl_stub PRIVATE ${THIRD_PARTY}/OpenCL-Headers)
set_target_properties(opencl_stub PROPERTIES
        ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/opencl-stub)

# Pre-seed FindOpenCL so ggml-opencl's find_package(OpenCL) resolves to our
# submodule headers + the stub.
set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
set(OpenCL_LIBRARY ${CMAKE_BINARY_DIR}/opencl-stub/libopencl_stub.a CACHE FILEPATH "" FORCE)

# ggml compiles its vulkan-shaders-gen tool FOR THE HOST during the Android
# build. On Windows that host build can't find ninja or a compiler on its
# own; hand it a toolchain file (MSVC via vcvars64 + the SDK's ninja).
if(CMAKE_HOST_WIN32 AND NOT GGML_VULKAN_SHADERS_GEN_TOOLCHAIN)
    file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" EDGELLM_HOST_NINJA)
    configure_file(host-toolchain-win.cmake.in
            ${CMAKE_BINARY_DIR}/host-toolchain-win.cmake @ONLY)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
            ${CMAKE_BINARY_DIR}/host-toolchain-win.cmake CACHE FILEPATH "" FORCE)
endif()

# The NDK bundles glslc (Vulkan shader compiler) but FindVulkan doesn't look
# in shader-tools; point it there for whichever host OS is building.
if(CMAKE_HOST_WIN32)
    set(Vulkan_GLSLC_EXECUTABLE
            ${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe)
elseif(CMAKE_HOST_APPLE)
    set(Vulkan_GLSLC_EXECUTABLE
            ${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64/glslc)
else()
    set(Vulkan_GLSLC_EXECUTABLE
            ${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc)
endif()

add_subdirectory(${LLAMA_DIR} llama.cpp-build)

if(TARGET ggml-opencl)
    add_dependencies(ggml-opencl opencl_stub)
endif()

add_library(edgellm_jni SHARED llama_jni.cpp)

target_link_libraries(edgellm_jni
        PRIVATE
        llama
        android
        log)
