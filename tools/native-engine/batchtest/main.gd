extends Node2D
# A synthetic UI-like 2D scene: cards (ninepatch panel, icon, label) that alternate textures item by item, flat
# rounded panels, plain rects, and a stack of overlapping see-through sprites whose order shows in the pixels.
# Saves the rendered frame as raw RGBA8 and prints the draw calls, so CI can check that canvas item reordering
# changes the draw count and not a single pixel. Everything is generated here; no game files.

func tex(color_a: Color, color_b: Color) -> ImageTexture:
	var img = Image.create(16, 16, false, Image.FORMAT_RGBA8)
	for y in 16:
		for x in 16:
			img.set_pixel(x, y, color_a if ((x / 4) + (y / 4)) % 2 == 0 else color_b)
	return ImageTexture.create_from_image(img)

func _ready() -> void:
	RenderingServer.set_default_clear_color(Color(0.1, 0.1, 0.12))
	var tex_a = tex(Color(0.9, 0.3, 0.2), Color(0.6, 0.1, 0.1))
	var tex_b = tex(Color(0.2, 0.5, 0.9), Color(0.1, 0.2, 0.6))
	var tex_panel = tex(Color(0.8, 0.8, 0.7), Color(0.5, 0.5, 0.45))
	var box = StyleBoxTexture.new()
	box.texture = tex_panel
	box.texture_margin_left = 4
	box.texture_margin_right = 4
	box.texture_margin_top = 4
	box.texture_margin_bottom = 4
	var flat = StyleBoxFlat.new()
	flat.bg_color = Color(0.3, 0.7, 0.4, 0.8)
	flat.set_corner_radius_all(6)
	# Cards: panel, icon, label, in that order, side by side and never overlapping.
	for row in 5:
		for col in 6:
			var x = 8 + col * 104
			var y = 8 + row * 56
			var panel = Panel.new()
			panel.position = Vector2(x, y)
			panel.size = Vector2(96, 48)
			panel.add_theme_stylebox_override("panel", box if (row + col) % 3 != 0 else flat)
			add_child(panel)
			var icon = Sprite2D.new()
			icon.texture = tex_a if (row + col) % 2 == 0 else tex_b
			icon.centered = false
			icon.position = Vector2(x + 4, y + 4)
			icon.scale = Vector2(2, 2)
			add_child(icon)
			var label = Label.new()
			label.text = "R%d C%d" % [row, col]
			label.position = Vector2(x + 40, y + 12)
			label.add_theme_color_override("font_color", Color(0.05, 0.05, 0.05))
			add_child(label)
			var bar = ColorRect.new()
			bar.color = Color(0.9, 0.8, 0.2, 0.7)
			bar.position = Vector2(x + 40, y + 38)
			bar.size = Vector2(40 + col * 2, 4)
			add_child(bar)
	# Overlapping see-through sprites, alternating textures: their order is visible, so none may move.
	for i in 12:
		var s = Sprite2D.new()
		s.texture = tex_a if i % 2 == 0 else tex_b
		s.centered = false
		s.position = Vector2(20 + i * 9, 300 + (i % 3) * 7)
		s.scale = Vector2(3, 3)
		s.modulate = Color(1, 1, 1, 0.6)
		add_child(s)
	# The same textures again further right, overlapping only some neighbours.
	for i in 12:
		var s = Sprite2D.new()
		s.texture = tex_b if i % 3 == 0 else tex_a
		s.centered = false
		s.position = Vector2(260 + (i % 4) * 40 + (i / 4) * 12, 300 + (i / 4) * 30)
		s.scale = Vector2(2.5, 2.5)
		s.modulate = Color(1, 1, 1, 0.7)
		add_child(s)
	for i in 4:
		await RenderingServer.frame_post_draw
	var draws = RenderingServer.get_rendering_info(RenderingServer.RENDERING_INFO_TOTAL_DRAW_CALLS_IN_FRAME)
	var img = get_viewport().get_texture().get_image()
	img.convert(Image.FORMAT_RGBA8)
	var out = OS.get_environment("BATCHTEST_OUT")
	var run_name = OS.get_environment("BATCHTEST_NAME")
	var f = FileAccess.open(out + "/" + run_name + ".raw", FileAccess.WRITE)
	f.store_32(img.get_width())
	f.store_32(img.get_height())
	f.store_buffer(img.get_data())
	f.close()
	print("BATCHTEST ", run_name, " draws ", draws, " size ", img.get_width(), "x", img.get_height(), " renderer ", RenderingServer.get_current_rendering_method())
	get_tree().quit(0)
