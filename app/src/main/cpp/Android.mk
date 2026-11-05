LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := controllerbridge
LOCAL_SRC_FILES := evdev_state.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := bridgespake
LOCAL_SRC_FILES := adb_spake_jni.c spake2/spake2.c spake2/sha512.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/spake2/include
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -fvisibility=hidden
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
