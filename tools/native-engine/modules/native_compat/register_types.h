// Kestrel native-engine compatibility module: clean-room stand-ins for the game's Spine and FMOD
// extensions. Written from public format notes and the game's own data only; no Esoteric or FMOD code.
#pragma once

#include "modules/register_module_types.h"

void initialize_native_compat_module(ModuleInitializationLevel p_level);
void uninitialize_native_compat_module(ModuleInitializationLevel p_level);
