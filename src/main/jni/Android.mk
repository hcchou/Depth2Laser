LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SDK_VERSION := 9
LOCAL_MODULE    := libjni_Depth2Laser
LOCAL_SRC_FILES := depthutils.cpp
LOCAL_LDFLAGS 	:= -llog

# Security Requirement: Add the defense flags
LOCAL_LDFLAGS 	+= -z noexecstack
LOCAL_LDFLAGS 	+= -z relro
LOCAL_LDFLAGS 	+= -z now

# Security Requirement: Add the defense flags
LOCAL_CFLAGS 	:= -fstack-protector
LOCAL_CFLAGS   += -fPIE -fPIC
LOCAL_CFLAGS   += -pie
LOCAL_CFLAGS   += -O2 -D_FORTIFY_SOURCE=2
LOCAL_CFLAGS   += -Wformat -Wformat-security

# Security Requirement: Add the defense flags
LOCAL_CPPFLAGS   += -fstack-protector
LOCAL_CPPFLAGS   += -fPIE -fPIC
LOCAL_CPPFLAGS   += -pie
LOCAL_CPPFLAGS   += -O2 -D_FORTIFY_SOURCE=2
LOCAL_CPPFLAGS   += -Wformat -Wformat-security

include $(BUILD_SHARED_LIBRARY)
