#include $(all-subdir-makefiles)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_VENDOR_MODULE := true

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := ModemNotifier

LOCAL_PROGUARD_ENABLED := full

LOCAL_CERTIFICATE := platform

LOCAL_SDK_VERSION := system_current

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
