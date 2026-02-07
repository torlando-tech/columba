cmake_minimum_required(VERSION 3.22)
project(lxst_native C)

# --- Prebuilt libcodec2 (LGPL-2.1) --------------------------------------
add_library(codec2 SHARED IMPORTED)
set_target_properties(codec2 PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libcodec2.so
)

# --- Prebuilt libopus (BSD-3-Clause) -------------------------------------
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopus.so
)

# --- lxst_codec2_jni (MIT) -----------------------------------------------
add_library(lxst_codec2_jni SHARED lxst_codec2_jni.c)
target_include_directories(lxst_codec2_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_codec2_jni codec2 log)

# --- lxst_opus_jni (MIT) -------------------------------------------------
add_library(lxst_opus_jni SHARED lxst_opus_jni.c)
target_include_directories(lxst_opus_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(lxst_opus_jni opus log)
