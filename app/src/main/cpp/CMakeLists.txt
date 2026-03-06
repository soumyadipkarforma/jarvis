cmake_minimum_required(VERSION 3.22.1)

project("jarvis-assistant")

# Set the path to llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Build llama.cpp as a library
add_subdirectory(${LLAMA_DIR})

# Create the JNI library
add_library(llama-android SHARED llama-jni.cpp)

# Link dependencies
target_link_libraries(llama-android
    llama
    log
)

# Include directories
target_include_directories(llama-android PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/ggml/include
)
