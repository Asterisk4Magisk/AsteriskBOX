.DEFAULT_GOAL := linux

override BUILD_DIR := build
SOURCES := $(sort $(wildcard *.c))

COMMON_REQUIRED_CFLAGS := -O3 -Wall -Wextra -Werror -fPIE
DEPENDENCY_FLAGS := -MMD -MP

SUPPORTED_ANDROID_ABIS := arm64-v8a armeabi-v7a x86 x86_64
ANDROID_API ?= 24
ANDROID_ABIS := $(if $(strip $(ABI)),$(filter $(strip $(ABI)),$(SUPPORTED_ANDROID_ABIS)),$(SUPPORTED_ANDROID_ABIS))
ANDROID_REQUESTED := $(filter android,$(MAKECMDGOALS))

ANDROID_TRIPLE_arm64-v8a := aarch64-linux-android
ANDROID_TRIPLE_armeabi-v7a := armv7a-linux-androideabi
ANDROID_TRIPLE_x86 := i686-linux-android
ANDROID_TRIPLE_x86_64 := x86_64-linux-android

normalize_path = $(subst \,/,$(strip $(1)))
latest_ndk_in = $(lastword $(sort $(wildcard $(if $(strip $(1)),$(call normalize_path,$(1))/ndk/*))))
android_clang = $(NDK_TOOLCHAIN_BIN)/$(ANDROID_TRIPLE_$(1))$(ANDROID_API)-clang$(ANDROID_CLANG_SUFFIX)

ifneq ($(ANDROID_REQUESTED),)
ifneq ($(strip $(ABI)),)
ifneq ($(words $(strip $(ABI))),1)
$(error Unsupported Android ABI '$(ABI)'. Supported ABIs: $(SUPPORTED_ANDROID_ABIS))
endif
ifeq ($(filter $(strip $(ABI)),$(SUPPORTED_ANDROID_ABIS)),)
$(error Unsupported Android ABI '$(ABI)'. Supported ABIs: $(SUPPORTED_ANDROID_ABIS))
endif
endif

NDK_ROOT := $(call normalize_path,$(ANDROID_NDK_HOME))
ifeq ($(NDK_ROOT),)
NDK_ROOT := $(call normalize_path,$(ANDROID_NDK_ROOT))
endif
ifeq ($(NDK_ROOT),)
NDK_ROOT := $(call latest_ndk_in,$(ANDROID_SDK_ROOT))
endif
ifeq ($(NDK_ROOT),)
NDK_ROOT := $(call latest_ndk_in,$(ANDROID_HOME))
endif
NDK_ROOT := $(patsubst %/,%,$(NDK_ROOT))

ifeq ($(NDK_ROOT),)
$(error Android NDK not found. Set ANDROID_NDK_HOME, ANDROID_NDK_ROOT, ANDROID_SDK_ROOT, or ANDROID_HOME)
endif

ifeq ($(OS),Windows_NT)
ANDROID_HOST_TAG := windows-x86_64
ANDROID_CLANG_SUFFIX := .cmd
else
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
ANDROID_HOST_TAG := linux-x86_64
ANDROID_CLANG_SUFFIX :=
else ifeq ($(UNAME_S),Darwin)
ANDROID_HOST_TAG := darwin-x86_64
ANDROID_CLANG_SUFFIX :=
else
$(error Unsupported Android build host '$(UNAME_S)')
endif
endif

NDK_TOOLCHAIN_BIN := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(ANDROID_HOST_TAG)/bin
ifeq ($(wildcard $(NDK_TOOLCHAIN_BIN)),)
$(error Android NDK host toolchain not found: $(NDK_TOOLCHAIN_BIN))
endif

$(foreach abi,$(ANDROID_ABIS),$(if $(wildcard $(call android_clang,$(abi))),,$(error Android NDK Clang not found: $(call android_clang,$(abi)))))
endif

LINUX_OBJ_DIR := $(BUILD_DIR)/linux/obj
LINUX_OUTPUT := $(BUILD_DIR)/linux/bpf2socks
LINUX_OBJECTS := $(patsubst %.c,$(LINUX_OBJ_DIR)/%.o,$(SOURCES))

.PHONY: linux clean

linux: $(LINUX_OUTPUT)

$(LINUX_OUTPUT): $(LINUX_OBJECTS)
	@mkdir -p "$(@D)"
	$(CC) $(LDFLAGS) -fPIE -pie $^ $(LDLIBS) -pthread -o "$@"

$(LINUX_OBJ_DIR)/%.o: %.c
	@mkdir -p "$(@D)"
	$(CC) $(CPPFLAGS) $(CFLAGS) $(COMMON_REQUIRED_CFLAGS) -pthread \
		$(DEPENDENCY_FLAGS) -MF "$(@:.o=.d)" -c "$<" -o "$@"

define define_android_rules
ANDROID_OBJ_DIR_$(1) := $(BUILD_DIR)/android/$(1)/obj
ANDROID_OUTPUT_$(1) := $(BUILD_DIR)/android/$(1)/libbpf2socks.so
ANDROID_OBJECTS_$(1) := $$(patsubst %.c,$$(ANDROID_OBJ_DIR_$(1))/%.o,$$(SOURCES))
ANDROID_API_STAMP_$(1) := $$(ANDROID_OBJ_DIR_$(1))/.api-$(ANDROID_API)

.PHONY: android-$(1)
android-$(1): $$(ANDROID_OUTPUT_$(1))

$$(ANDROID_API_STAMP_$(1)):
	@mkdir -p "$$(@D)"
	@rm -f "$$(@D)"/.api-*
	@touch "$$@"

$$(ANDROID_OBJECTS_$(1)): $$(ANDROID_API_STAMP_$(1))

$$(ANDROID_OUTPUT_$(1)): $$(ANDROID_OBJECTS_$(1))
	@mkdir -p "$$(@D)"
	"$(call android_clang,$(1))" $$(ANDROID_LDFLAGS) -fPIE -pie $$^ \
		$$(ANDROID_LDLIBS) -o "$$@"

$$(ANDROID_OBJ_DIR_$(1))/%.o: %.c
	@mkdir -p "$$(@D)"
	"$(call android_clang,$(1))" $$(ANDROID_CPPFLAGS) $$(ANDROID_CFLAGS) \
		$$(COMMON_REQUIRED_CFLAGS) $$(DEPENDENCY_FLAGS) \
		-MF "$$(@:.o=.d)" -c "$$<" -o "$$@"
endef

$(foreach abi,$(SUPPORTED_ANDROID_ABIS),$(eval $(call define_android_rules,$(abi))))

.PHONY: android
android: $(addprefix android-,$(ANDROID_ABIS))

clean:
	rm -rf -- "$(BUILD_DIR)"

-include $(LINUX_OBJECTS:.o=.d)
-include $(foreach abi,$(SUPPORTED_ANDROID_ABIS),$(ANDROID_OBJECTS_$(abi):.o=.d))
