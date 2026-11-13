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
	var sound_ok = sound_test()
	get_tree().quit(0 if err == OK and same and sound_ok else 1)

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

func shot(tag: String) -> void:
	await RenderingServer.frame_post_draw
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	var path := OS.get_environment("SKELTEST_OUT").path_join(tag + ".png")
	img.save_png(path)
	print("SKELTEST ", tag, " tip=", sk.get_bone_position("tip"), " rot=", sk.get_bone_rotation("arm"), " head=", sk.get_slot_attachment("head"), " png=", path)
