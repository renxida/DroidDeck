extends Node2D
# Loads the test rig into NativeSkeleton2D, saves two frames of the "wave" animation and prints
# what a checker needs. Untyped on purpose: the editor that exports this doesn't know the class.

var sk

func _ready() -> void:
	RenderingServer.set_default_clear_color(Color.BLACK)
	if not ClassDB.class_exists("NativeSkeleton2D"):
		print("SKELTEST missing class")
		get_tree().quit(2)
		return
	sk = ClassDB.instantiate("NativeSkeleton2D")
	sk.position = Vector2(128, 220)
	sk.playing = false
	add_child(sk)
	var err = sk.load_files("res://rig/rig.json", "res://rig/rig.atlas")
	print("SKELTEST load ", err, " '", sk.get_load_error(), "' anims ", sk.get_animation_names())
	sk.animation_event.connect(func(t, a, e, i, f, s): print("SKELTEST event ", e, " ", i))
	sk.play(0, "wave", true)
	sk.advance(0.0)
	await shot("a")
	sk.advance(0.5)
	await shot("b")
	# The same rig in the binary layout (rig.skel, made by make_binary_rig.py) must pose identically.
	var sb = ClassDB.instantiate("NativeSkeleton2D")
	sb.playing = false
	sb.visible = false
	add_child(sb)
	var berr = sb.load_files("res://rig/rig.skel", "res://rig/rig.atlas")
	sb.play(0, "wave", true)
	sb.advance(0.0)
	sb.advance(0.5)
	var same = berr == OK and sb.get_bone_position("tip").distance_to(sk.get_bone_position("tip")) < 0.01
	print("SKELTEST binary load ", berr, " '", sb.get_load_error(), "' tip=", sb.get_bone_position("tip"), " same=", same)
	var sound_ok = sound_test() and paths_test() and fmod_test()
	sound_ok = await event_sound_test() and sound_ok
	var spine_ok = spine_compat_test(sb)
	get_tree().quit(0 if err == OK and same and sound_ok and spine_ok else 1)

# The Spine-compatible classes (spine_compat.h) driven the way a game's scenes and code drive them:
# imported files through the resource loader, a text scene with a SpineSprite, the documented
# animation state calls and the four-argument event signal. [reference] is the same rig posed by
# NativeSkeleton2D at 0.5 s.
func spine_compat_test(reference) -> bool:
	for c in ["SpineSprite", "SpineSkeletonDataResource", "SpineAnimationState", "SpineTrackEntry"]:
		if not ClassDB.class_exists(c):
			print("SKELTEST spine missing class ", c)
			return false
	var skel_bytes := FileAccess.get_file_as_bytes("res://rig/rig.skel")
	var f := FileAccess.open("user://compat_rig.spskel", FileAccess.WRITE)
	f.store_buffer(skel_bytes)
	f.close()
	f = FileAccess.open("user://compat_rig.spatlas", FileAccess.WRITE)
	f.store_string(JSON.stringify({"atlas_data": FileAccess.get_file_as_string("res://rig/rig.atlas"),
		"normal_texture_prefix": "n", "source_path": "res://rig/rig.atlas", "specular_texture_prefix": "s"}))
	f.close()
	f = FileAccess.open("user://compat.tscn", FileAccess.WRITE)
	f.store_string("""[gd_scene load_steps=5 format=3]

[ext_resource type="SpineSkeletonFileResource" path="user://compat_rig.spskel" id="1_s"]
[ext_resource type="SpineAtlasResource" path="user://compat_rig.spatlas" id="2_a"]

[sub_resource type="SpineAnimationMix" id="Mix_1"]
from = "wave"
to = "wave"
mix = 0.0

[sub_resource type="SpineSkeletonDataResource" id="Data_1"]
atlas_res = ExtResource("2_a")
skeleton_file_res = ExtResource("1_s")
animation_mixes = [SubResource("Mix_1")]
default_mix = 0.0

[node name="Body" type="SpineSprite"]
skeleton_data_res = SubResource("Data_1")
update_mode = 2
preview_animation = "wave"
debug_bones = false

[node name="Tip" type="SpineBoneNode" parent="."]
bone_name = "tip"
""")
	f.close()
	var packed = ResourceLoader.load("user://compat.tscn")
	if packed == null:
		print("SKELTEST spine scene did not load")
		return false
	var sp = packed.instantiate()
	sp.visible = false
	add_child(sp)
	var counts := {"started": 0, "completed": 0, "event": 0}
	sp.animation_started.connect(func(s, st, e): counts.started += 1)
	sp.animation_completed.connect(func(s, st, e): counts.completed += 1)
	sp.animation_event.connect(func(s, st, e, ev): counts.event += 1)
	var data = sp.skeleton_data_res
	var state = sp.get_animation_state()
	var entry = state.set_animation("wave", true, 0) if state != null else null
	if entry == null:
		print("SKELTEST spine no entry; loaded=", data.is_skeleton_data_loaded() if data else false, " '", data.get_load_error() if data else "", "'")
		return false
	sp.update_skeleton(0.0)
	sp.update_skeleton(0.5)
	var tip = sp.get_global_bone_transform("tip").origin - sp.global_position
	var follow = sp.get_node("Tip").position
	var want = reference.get_bone_position("tip")
	var duration = entry.get_animation().get_duration()
	sp.update_skeleton(duration)
	var queued = state.add_animation("wave", 0.0, false, 1)
	var ok = tip.distance_to(want) < 0.01 and follow.distance_to(want) < 0.01 and counts.started >= 1 \
		and counts.completed >= 1 and entry.get_track_index() == 0 and queued != null and state.get_current(0) == entry \
		and data.get_animation_names().has("wave") and sp.get_skeleton().find_bone("tip").get_name() == "tip"
	print("SKELTEST spine tip=", tip, " follow=", follow, " want=", want, " counts=", counts, " duration=", duration, " ok=", ok)
	return ok

# Decodes sound/tone.fsb (made by make_sound_bank.py: 0.5 s, 440 Hz left, 660 Hz right) through
# NativeSoundBank and the engine's own Vorbis stream, and checks length, level and pitch.
func sound_test() -> bool:
	if not ClassDB.class_exists("NativeSoundBank"):
		print("SKELTEST sound missing class")
		return false
	var bank = ClassDB.instantiate("NativeSoundBank")
	var err = bank.load("res://sound/tone.fsb")
	var stream = bank.get_stream(bank.find_sound("tone")) if err == OK else null
	if stream == null:
		print("SKELTEST sound load ", err, " '", bank.get_load_error(), "'")
		return false
	var playback = stream.instantiate_playback()
	playback.start(0.0)
	var rate = AudioServer.get_mix_rate()
	var frames = playback.mix_audio(1.0, int(rate * 0.4))
	var sum = Vector2.ZERO
	var crossings = Vector2.ZERO
	for i in range(frames.size()):
		var f = frames[i]
		sum += f * f
		if i > 0:
			var p = frames[i - 1]
			if (p.x < 0.0) != (f.x < 0.0):
				crossings.x += 1
			if (p.y < 0.0) != (f.y < 0.0):
				crossings.y += 1
	var seconds = frames.size() / rate
	var rms = Vector2(sqrt(sum.x / frames.size()), sqrt(sum.y / frames.size()))
	var hz = crossings / (2.0 * seconds)
	var ok = absf(stream.get_length() - 0.5) < 0.01 and absf(rms.x - 0.354) < 0.05 and absf(rms.y - 0.354) < 0.05 \
		and absf(hz.x - 440) < 10 and absf(hz.y - 660) < 10
	print("SKELTEST sound length=", stream.get_length(), " rms=", rms, " hz=", hz, " ok=", ok)
	return ok

# Reads sound/paths.strings.bank (synthetic, written by fsb/tests/path_table_test --write) through
# NativeSoundBank's path table: paths, GUIDs both ways, case-insensitive lookup, prefix listing.
func paths_test() -> bool:
	var bank = ClassDB.instantiate("NativeSoundBank")
	var err = bank.load("res://sound/paths.strings.bank")
	var guid = bank.find_guid_for_path("bus:/a") if err == OK else ""
	var ok = err == OK and bank.get_path_count() == 3 and bank.get_path(1) == "Event:/x" \
		and guid == "{00000003-0000-4000-8000-000000000003}" and bank.find_path_for_guid(guid) == "bus:/a" \
		and bank.find_guid_for_path("event:/X") == bank.get_path_guid(1) and bank.find_guid_for_path("bus:/b") == "" \
		and bank.get_paths_with_prefix("bus:/") == PackedStringArray(["bus:/", "bus:/a"])
	print("SKELTEST paths load ", err, " count=", bank.get_path_count(), " guid=", guid, " ok=", ok)
	return ok

# The FMOD-compatible classes: the FmodServer singleton reads the synthetic strings bank, resolves paths to
# GUIDs, tracks an event instance, buses and global parameters; an emitter node plays and stops.
func fmod_test() -> bool:
	if not Engine.has_singleton("FmodServer"):
		print("SKELTEST fmod missing singleton")
		return false
	var server = Engine.get_singleton("FmodServer")
	var bank = server.load_bank("res://sound/paths.strings.bank", 0)
	var desc = server.get_event("Event:/x")
	var inst = server.create_event_instance("Event:/x")
	inst.set_parameter_by_name("intensity", 0.5)
	inst.start()
	var playing = inst.get_playback_state() == 0
	inst.stop(1)
	server.set_global_parameter_by_name("act", 2.0)
	var bus = server.get_bus("bus:/a")
	bus.set_volume(0.25)
	var emitter = ClassDB.instantiate("FmodEventEmitter2D")
	emitter.event_name = "Event:/x"
	var signals := []
	emitter.started.connect(func(): signals.append("started"))
	emitter.stopped.connect(func(): signals.append("stopped"))
	add_child(emitter)
	emitter.play()
	emitter.set_parameter("intensity", 0.75)
	var emitter_playing = emitter.is_playing()
	emitter.stop()
	emitter.queue_free()
	var stats = server.get_compat_stats()
	var ok = bank.get_bus_count() == 2 and desc.get_guid() == "{00000002-0000-4000-8000-000000000002}" \
		and server.check_event_path("event:/x") and not server.check_event_path("event:/nope") and playing \
		and inst.get_playback_state() == 2 and absf(inst.get_parameter_by_name("intensity") - 0.5) < 1e-6 \
		and server.get_global_parameter_by_name("act") == 2.0 and server.get_bus("bus:/a").get_volume() == 0.25 \
		and emitter_playing and signals == ["started", "stopped"] and absf(emitter.get_parameter("intensity") - 0.75) < 1e-6
	print("SKELTEST fmod buses=", bank.get_bus_count(), " guid=", desc.get_guid(), " signals=", signals, " stats=", stats, " ok=", ok)
	return ok

# A bank's own event metadata (sound/events.bank, made by make_event_bank.py): starting "Event:/x" plays
# sound 0 of the embedded tone through an audio player, which reaches the Master bus, and the instance
# reads as stopped once the 0.5 s tone has ended. A one-shot plays too.
func event_sound_test() -> bool:
	var server = Engine.get_singleton("FmodServer")
	var bank = server.load_bank("res://sound/events.bank", 0)
	var capture = AudioEffectCapture.new()
	AudioServer.add_bus_effect(0, capture)
	var before = server.get_compat_stats()["sounds_started"]
	var inst = server.create_event_instance("event:/x")
	inst.start()
	var started = server.get_compat_stats()["sounds_started"] - before
	await get_tree().create_timer(0.3).timeout
	capture.clear_buffer()
	await get_tree().create_timer(0.1).timeout
	var frames = capture.get_buffer(capture.get_frames_available())
	var sum := 0.0
	for f in frames:
		sum += f.x * f.x + f.y * f.y
	var rms = sqrt(sum / max(1, frames.size() * 2))
	var playing_mid = inst.get_playback_state() == 0
	await get_tree().create_timer(0.6).timeout
	var ended = inst.get_playback_state() == 2
	server.play_one_shot("event:/x")
	await get_tree().create_timer(0.7).timeout # let it end: a sound still playing at quit reads as a leak
	var stats = server.get_compat_stats()
	AudioServer.remove_bus_effect(0, AudioServer.get_bus_effect_count(0) - 1)
	var ok = bank.get_event_description_count() == 1 and started == 1 and frames.size() > 0 and rms > 0.05 \
		and playing_mid and ended and stats["sounds_started"] - before == 2
	print("SKELTEST event sound started=", started, " frames=", frames.size(), " rms=", rms, " playing_mid=",
		playing_mid, " ended=", ended, " stats=", stats, " ok=", ok)
	return ok

func shot(tag: String) -> void:
	await RenderingServer.frame_post_draw
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	var path := OS.get_environment("SKELTEST_OUT").path_join(tag + ".png")
	img.save_png(path)
	print("SKELTEST ", tag, " tip=", sk.get_bone_position("tip"), " rot=", sk.get_bone_rotation("arm"), " head=", sk.get_slot_attachment("head"), " png=", path)
