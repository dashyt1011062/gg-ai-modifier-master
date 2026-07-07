cmake_minimum_required(VERSION 3.22.1)
project("aigg_native")

# V19 高性能版本（使用 process_vm_readv）- JNI 库
add_library(aigg_scanner SHARED scanner_v19.cpp)

find_library(log-lib log)
target_link_libraries(aigg_scanner ${log-lib})

# 开启性能优化
set_target_properties(aigg_scanner PROPERTIES C_STANDARD 99 CXX_STANDARD 11)
target_compile_options(aigg_scanner PRIVATE -O3 -ffast-math -funroll-loops)

# Root 独立可执行文件（优化版：使用 pread64，参考 C-Android-Memory-Tool）
add_executable(scanner_root scanner_root_optimized.cpp)
set_target_properties(scanner_root PROPERTIES CXX_STANDARD 11)
target_compile_options(scanner_root PRIVATE -O3 -ffast-math -funroll-loops -static-libstdc++)
target_link_options(scanner_root PRIVATE -static-libstdc++)
