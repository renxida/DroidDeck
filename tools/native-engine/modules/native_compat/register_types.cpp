#include "register_types.h"

#include "core/string/print_string.h"
#include "core/variant/variant.h"

void initialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
	print_verbose("native_compat: module loaded (no classes yet)");
}

void uninitialize_native_compat_module(ModuleInitializationLevel p_level) {
}
