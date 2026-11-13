// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
#include "register_types.h"

#include "native_skeleton_2d.h"
#include "native_sound_bank.h"

#include "core/object/class_db.h"
#include "core/string/print_string.h"
#include "core/variant/variant.h"

void initialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
	GDREGISTER_CLASS(NativeSkeleton2D);
	GDREGISTER_CLASS(NativeSoundBank);
	print_verbose("native_compat: module loaded (NativeSkeleton2D, NativeSoundBank)");
}

void uninitialize_native_compat_module(ModuleInitializationLevel p_level) {
}
