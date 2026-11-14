// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
#include "register_types.h"

#include "fmod_compat.h"
#include "native_skeleton_2d.h"
#include "native_sound_bank.h"
#include "spine_compat.h"

#include "core/config/engine.h"
#include "core/io/resource_loader.h"

#include "core/object/class_db.h"
#include "core/string/print_string.h"
#include "core/variant/variant.h"

static Ref<SpineResourceLoader> spine_loader;
static FmodServer *fmod_server = nullptr;

void initialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
	GDREGISTER_CLASS(NativeSkeleton2D);
	GDREGISTER_CLASS(NativeSoundBank);

	// Under the names the game's scenes and code use (see spine_compat.h).
	GDREGISTER_CLASS(SpineAtlasResource);
	GDREGISTER_CLASS(SpineSkeletonFileResource);
	GDREGISTER_CLASS(SpineAnimationMix);
	GDREGISTER_CLASS(SpineSkeletonDataResource);
	GDREGISTER_CLASS(SpineAnimation);
	GDREGISTER_CLASS(SpineBoneData);
	GDREGISTER_CLASS(SpineSlotData);
	GDREGISTER_CLASS(SpineSkin);
	GDREGISTER_CLASS(SpineEventData);
	GDREGISTER_CLASS(SpineEvent);
	GDREGISTER_CLASS(SpineTrackEntry);
	GDREGISTER_CLASS(SpineAnimationState);
	GDREGISTER_CLASS(SpineBone);
	GDREGISTER_CLASS(SpineSlot);
	GDREGISTER_CLASS(SpineSkeleton);
	GDREGISTER_CLASS(SpineSprite);
	GDREGISTER_CLASS(SpineBoneNode);
	GDREGISTER_CLASS(SpineSlotNode);
	GDREGISTER_CLASS(SpineMesh2D);
	GDREGISTER_CLASS(SpineResourceLoader);
	// Under the names of the game's FMOD plugin (see fmod_compat.h).
	GDREGISTER_CLASS(FmodParameterDescription);
	GDREGISTER_CLASS(FmodEvent);
	GDREGISTER_CLASS(FmodEventDescription);
	GDREGISTER_CLASS(FmodBus);
	GDREGISTER_CLASS(FmodVCA);
	GDREGISTER_CLASS(FmodBank);
	GDREGISTER_CLASS(FmodServer);
	GDREGISTER_CLASS(FmodEventEmitter2D);
	GDREGISTER_CLASS(FmodEventEmitter3D);
	GDREGISTER_CLASS(FmodListener2D);
	GDREGISTER_CLASS(FmodListener3D);
	GDREGISTER_CLASS(FmodBankLoader);
	fmod_server = memnew(FmodServer);
	Engine::get_singleton()->add_singleton(Engine::Singleton("FmodServer", fmod_server));
	spine_loader.instantiate();
	ResourceLoader::add_resource_format_loader(spine_loader);
	print_verbose("native_compat: module loaded (NativeSkeleton2D, NativeSoundBank, Spine- and FMOD-compatible classes)");
}

void uninitialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
	if (spine_loader.is_valid()) {
		ResourceLoader::remove_resource_format_loader(spine_loader);
		spine_loader.unref();
	}
	if (fmod_server) {
		Engine::get_singleton()->remove_singleton("FmodServer");
		memdelete(fmod_server);
		fmod_server = nullptr;
	}
}
