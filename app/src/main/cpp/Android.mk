LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := dpkg-deb-wrapper
LOCAL_SRC_FILES := dpkg-deb-wrapper.c
LOCAL_LDFLAGS := -pie
include $(BUILD_EXECUTABLE)

# Otomasi penyalinan ELF PIE executable ke jniLibs agar dikemas oleh AGP
WRAPPER_SRC := $(LOCAL_BUILT_MODULE)
WRAPPER_DST := $(LOCAL_PATH)/../jniLibs/$(TARGET_ARCH_ABI)/libdpkg-deb-wrapper.so

$(WRAPPER_DST): $(WRAPPER_SRC)
	@mkdir -p $(dir $@)
	cp -f $< $@

include $(CLEAR_VARS)
LOCAL_MODULE := pa-service
LOCAL_SRC_FILES := pa-service.c
LOCAL_LDFLAGS := -pie
include $(BUILD_EXECUTABLE)

PA_SERVICE_SRC := $(LOCAL_BUILT_MODULE)
PA_SERVICE_DST := $(LOCAL_PATH)/../jniLibs/$(TARGET_ARCH_ABI)/libpa-service.so

$(PA_SERVICE_DST): $(PA_SERVICE_SRC)
	@mkdir -p $(dir $@)
	cp -f $< $@

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# Pastikan seluruh jniLibs terisi sebelum shared library bootstrap selesai
$(LOCAL_BUILT_MODULE): $(WRAPPER_DST) $(PA_SERVICE_DST)
