cmake_minimum_required(VERSION 3.22.1)

project("jarvis-assistant")

# Set the path to llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Build llama.cpp as a library
add_subdirectory(${LLAMA_DIR})

# Create the LLM JNI library
add_library(llama-android SHARED llama-jni.cpp)

# Create the TTS JNI library
add_library(coqui-tts-android SHARED tts-jni.cpp)

# Link dependencies for llama
target_link_libraries(llama-android
    llama
    log
)

# Link dependencies for tts
target_link_libraries(coqui-tts-android
    log
    m # link math library for sinf
)

# Include directories
target_include_directories(llama-android PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/src
    ${LLAMA_DIR}/ggml/include
)
