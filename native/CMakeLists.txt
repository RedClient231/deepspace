cmake_minimum_required(VERSION 3.22.1)
project("vengine" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Source files
add_library(vengine SHARED
    src/main.cpp
    src/hook.cpp
    src/io_redirect.cpp
    src/proc_virtual.cpp
    src/memory_bridge.cpp
    src/su_stub.cpp
)

# Include directories
target_include_directories(vengine PRIVATE
    include
)

# Link libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(vengine
    ${log-lib}
    ${android-lib}
    dl
)

# Compiler flags
target_compile_options(vengine PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

# Strip in release
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -s")
