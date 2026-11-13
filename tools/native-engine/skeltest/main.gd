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
	get_tree().quit(0 if err == OK and same else 1)

func shot(tag: String) -> void:
	await RenderingServer.frame_post_draw
	await RenderingServer.frame_post_draw
	var img := get_viewport().get_texture().get_image()
	var path := OS.get_environment("SKELTEST_OUT").path_join(tag + ".png")
	img.save_png(path)
	print("SKELTEST ", tag, " tip=", sk.get_bone_position("tip"), " rot=", sk.get_bone_rotation("arm"), " head=", sk.get_slot_attachment("head"), " png=", path)
