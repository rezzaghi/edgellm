cmake_minimum_required(VERSION 3.22)
project(edgellm_llamacpp CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

# Build llama.cpp statically into our single JNI .so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp-build)

add_library(edgellm_jni SHARED llama_jni.cpp)

target_link_libraries(edgellm_jni
        PRIVATE
        llama
        android
        log)
