extends Node
# Rebuilds one frame of a canvas trace (patches/canvas_trace.py) through the RenderingServer, so a real scene's
# batching can be measured and its pixels compared across renderer arms on lavapipe.
#
#   REPLAY_TRACE=<file>  BATCHTEST_OUT=<dir>  BATCHTEST_NAME=<arm>  [BATCHTEST_SECONDS=<hold>]
#
# Every canvas_render_items call becomes a canvas of its own, stacked in the order of the trace, with the call's
# canvas transform. Every item becomes a canvas item in the same order with its final transform, modulate,
# material, back-buffer copy and texture filter; an item clipped by an earlier one becomes that item's child so
# the clip rect applies. Textures are placeholders of the same size, one per traced texture id, and materials a
# trivial shader, one material per traced material id, so the renderer starts and breaks batches where it did.
# Polygons, meshes and multimeshes are rebuilt as that many triangles or instances within their traced bounds.
# Prints what it could not rebuild faithfully ("REPLAY approximations"), then "BATCHTEST <arm> draws N" like the
# synthetic scene, and saves the frame as raw RGBA8.

var textures := {}
var materials := {}
var meshes := {}
var shader: Shader
var approx := {}
var keep := [] # resources the RenderingServer only holds by RID


func note(what: String) -> void:
	approx[what] = approx.get(what, 0) + 1


func nums(t: PackedStringArray, key: String, n: int) -> PackedFloat32Array:
	var out := PackedFloat32Array()
	var i := t.find(key)
	if i < 0:
		return out
	for j in n:
		out.append(t[i + 1 + j].to_float())
	return out


func xf_of(v: PackedFloat32Array) -> Transform2D:
	return Transform2D(Vector2(v[0], v[1]), Vector2(v[2], v[3]), Vector2(v[4], v[5]))


func rect_of(v: PackedFloat32Array) -> Rect2:
	return Rect2(v[0], v[1], v[2], v[3])


func color_of(v: PackedFloat32Array) -> Color:
	return Color(v[0], v[1], v[2], v[3])


func tex_of(t: PackedStringArray) -> RID:
	var v := nums(t, "tex", 3)
	var id := int(v[0])
	if id == 0:
		return RID()
	if not textures.has(id):
		var w := clampi(int(v[1]), 1, 4096)
		var h := clampi(int(v[2]), 1, 4096)
		var img := Image.create(w, h, false, Image.FORMAT_RGBA8)
		var hue := float(hash(id) % 997) / 997.0
		img.fill(Color.from_hsv(hue, 0.5, 0.9, 0.9))
		img.fill_rect(Rect2i(0, 0, maxi(w / 2, 1), maxi(h / 2, 1)), Color.from_hsv(hue, 0.7, 0.5, 1.0))
		img.fill_rect(Rect2i(w / 2, h / 2, maxi(w - w / 2, 1), maxi(h - h / 2, 1)), Color.from_hsv(hue, 0.3, 0.7, 0.6))
		var tex := ImageTexture.create_from_image(img)
		keep.append(tex)
		textures[id] = tex.get_rid()
	return textures[id]


func material_of(id: int) -> RID:
	if id == 0:
		return RID()
	if not materials.has(id):
		var m := ShaderMaterial.new()
		m.shader = shader
		var hue := float(hash(id) % 991) / 991.0
		m.set_shader_parameter("tint", Color.from_hsv(hue, 0.2, 1.0, 1.0))
		keep.append(m)
		materials[id] = m.get_rid()
	return materials[id]


func mesh_of(surfaces: int, bounds: Rect2) -> RID:
	var key := "%d %s" % [surfaces, bounds]
	if not meshes.has(key):
		var m := ArrayMesh.new()
		for s in maxi(surfaces, 1):
			var arrays := []
			arrays.resize(Mesh.ARRAY_MAX)
			var r := bounds
			arrays[Mesh.ARRAY_VERTEX] = PackedVector2Array([r.position, r.position + Vector2(r.size.x, 0), r.end, r.position + Vector2(0, r.size.y)])
			arrays[Mesh.ARRAY_TEX_UV] = PackedVector2Array([Vector2(0, 0), Vector2(1, 0), Vector2(1, 1), Vector2(0, 1)])
			arrays[Mesh.ARRAY_INDEX] = PackedInt32Array([0, 1, 2, 0, 2, 3])
			m.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays)
		keep.append(m)
		meshes[key] = m.get_rid()
	return meshes[key]


func triangles_in(r: Rect2, count: int) -> Dictionary:
	# A strip of count triangles across the bounds, so the polygon covers the same area with the same work.
	var cols := maxi((count + 1) / 2, 1)
	var points := PackedVector2Array()
	for i in cols + 1:
		var x := r.position.x + r.size.x * float(i) / float(cols)
		points.append(Vector2(x, r.position.y))
		points.append(Vector2(x, r.end.y))
	var indices := PackedInt32Array()
	for t in count:
		var c := t / 2
		if t % 2 == 0:
			indices.append_array([c * 2, c * 2 + 1, c * 2 + 2])
		else:
			indices.append_array([c * 2 + 1, c * 2 + 3, c * 2 + 2])
	var uvs := PackedVector2Array()
	for p in points:
		uvs.append((p - r.position) / Vector2(maxf(r.size.x, 0.001), maxf(r.size.y, 0.001)))
	return {"points": points, "indices": indices, "uvs": uvs}


func add_command(item: RID, t: PackedStringArray) -> void:
	match t[0]:
		"rect":
			var r := rect_of(nums(t, "r", 4))
			var src := rect_of(nums(t, "src", 4))
			var c := color_of(nums(t, "c", 4))
			var flags := int(nums(t, "flags", 1)[0])
			var tex := tex_of(t)
			if not tex.is_valid():
				RenderingServer.canvas_item_add_rect(item, r, c)
			elif flags & 128:
				RenderingServer.canvas_item_add_msdf_texture_rect_region(item, r, tex, src, c, int(nums(t, "outline", 1)[0]), nums(t, "px", 1)[0])
			elif flags & 256:
				RenderingServer.canvas_item_add_lcd_texture_rect_region(item, r, tex, src, c)
			elif flags & 1:
				RenderingServer.canvas_item_add_texture_rect_region(item, r, tex, src, c, (flags & 16) != 0, (flags & 32) != 0)
			else:
				RenderingServer.canvas_item_add_texture_rect(item, r, tex, (flags & 2) != 0, c, (flags & 16) != 0)
		"ninepatch":
			var m := nums(t, "margin", 4)
			var axis := nums(t, "axis", 2)
			RenderingServer.canvas_item_add_nine_patch(item, rect_of(nums(t, "r", 4)), rect_of(nums(t, "src", 4)), tex_of(t),
					Vector2(m[0], m[1]), Vector2(m[2], m[3]), int(axis[0]), int(axis[1]), int(nums(t, "center", 1)[0]) != 0,
					color_of(nums(t, "c", 4)))
		"polygon":
			if int(nums(t, "prim", 1)[0]) != RenderingServer.PRIMITIVE_TRIANGLES:
				note("polygon that is not a triangle list, drawn as triangles")
			if int(nums(t, "bones", 1)[0]) != 0:
				note("skinned polygon, drawn unskinned")
			var tris := int(nums(t, "triangles", 1)[0])
			if tris <= 0:
				note("polygon with no recorded triangle count, drawn as two")
				tris = 2
			var g := triangles_in(rect_of(nums(t, "bounds", 4)), tris)
			var ncol := int(nums(t, "colors", 1)[0])
			var colors := PackedColorArray()
			if ncol == 1:
				colors.append(Color(1, 1, 1, 0.9))
			elif ncol > 1:
				for i in g.points.size():
					colors.append(Color.from_hsv(float(i % 7) / 7.0, 0.3, 1.0, 0.9))
			var uvs: PackedVector2Array = g.uvs if int(nums(t, "uvs", 1)[0]) != 0 else PackedVector2Array()
			RenderingServer.canvas_item_add_triangle_array(item, g.indices, g.points, colors, uvs, PackedInt32Array(), PackedFloat32Array(), tex_of(t))
		"primitive":
			var points := PackedVector2Array()
			var uvs := PackedVector2Array()
			var colors := PackedColorArray()
			var i := t.find("p")
			while i >= 0 and i + 9 < t.size():
				points.append(Vector2(t[i + 1].to_float(), t[i + 2].to_float()))
				uvs.append(Vector2(t[i + 3].to_float(), t[i + 4].to_float()))
				colors.append(Color(t[i + 6].to_float(), t[i + 7].to_float(), t[i + 8].to_float(), t[i + 9].to_float()))
				var next := Array(t.slice(i + 1)).find("p")
				i = -1 if next < 0 else i + 1 + next
			if points.size() > 0:
				RenderingServer.canvas_item_add_primitive(item, points, colors, uvs, tex_of(t))
		"mesh":
			note("mesh, drawn as a quad per surface within its bounds")
			var mesh := mesh_of(int(nums(t, "surfaces", 1)[0]), rect_of(nums(t, "bounds", 4)))
			RenderingServer.canvas_item_add_mesh(item, mesh, xf_of(nums(t, "xf", 6)), color_of(nums(t, "c", 4)), tex_of(t))
		"multimesh":
			note("multimesh, drawn as quads at the origin")
			var n := maxi(int(nums(t, "instances", 1)[0]), 1)
			var mm := RenderingServer.multimesh_create()
			RenderingServer.multimesh_set_mesh(mm, mesh_of(1, Rect2(0, 0, 8, 8)))
			RenderingServer.multimesh_allocate_data(mm, n, RenderingServer.MULTIMESH_TRANSFORM_2D)
			for k in n:
				RenderingServer.multimesh_instance_set_transform_2d(mm, k, Transform2D(0, Vector2(k * 9 % 400, k * 9 / 400 * 9)))
			RenderingServer.canvas_item_add_multimesh(item, mm, tex_of(t))
		"particles":
			note("particles, left out")
		"transform":
			RenderingServer.canvas_item_add_set_transform(item, xf_of(nums(t, "transform", 6)))
		"clipignore":
			RenderingServer.canvas_item_add_clip_ignore(item, int(t[1]) != 0)
		"slice":
			RenderingServer.canvas_item_add_animation_slice(item, t[1].to_float(), t[2].to_float(), t[3].to_float(), t[4].to_float())
		_:
			note("command %s, left out" % t[0])


func _ready() -> void:
	shader = Shader.new()
	shader.code = "shader_type canvas_item;\nuniform vec4 tint : source_color = vec4(1.0);\nvoid fragment() { COLOR *= tint; }\n"
	var path := OS.get_environment("REPLAY_TRACE")
	var f := FileAccess.open(path, FileAccess.READ)
	if f == null:
		push_error("REPLAY: cannot open REPLAY_TRACE %s" % path)
		get_tree().quit(1)
		return
	var vp := get_viewport().get_viewport_rid()
	var size := Vector2i(640, 480)
	var layer := 0
	var canvas := RID()
	var items := [] # this call's items, by index
	var item_xf := []
	var item := RID()
	var last_owner := -1
	var calls := 0
	var count := 0
	while not f.eof_reached():
		var line := f.get_line()
		if line.is_empty():
			continue
		var t := line.strip_edges().split(" ", false)
		if line.begins_with("call "):
			var tv := nums(t, "target", 3)
			size = Vector2i(maxi(size.x, int(tv[1])), maxi(size.y, int(tv[2])))
			if int(nums(t, "lights", 2)[0]) + int(nums(t, "lights", 2)[1]) > 0:
				note("2D lights, left out")
			canvas = RenderingServer.canvas_create()
			RenderingServer.viewport_attach_canvas(vp, canvas)
			RenderingServer.viewport_set_canvas_stacking(vp, canvas, layer, 0)
			RenderingServer.viewport_set_canvas_transform(vp, canvas, xf_of(nums(t, "canvas", 6)))
			layer += 1
			calls += 1
			items = []
			item_xf = []
			last_owner = -1
		elif line.begins_with("item "):
			var xf := xf_of(nums(t, "xf", 6))
			var clip := nums(t, "clip", 2)
			var owner := int(clip[1])
			item = RenderingServer.canvas_item_create()
			var parent := canvas
			var local := xf
			if owner >= 0 and owner < items.size():
				var oxf: Transform2D = item_xf[owner]
				if absf(oxf.determinant()) > 1e-6:
					parent = items[owner]
					local = oxf.affine_inverse() * xf
					if owner != items.size() - 1 and owner != last_owner:
						note("clipped item not right after its clip owner (draw order may differ)")
				else:
					note("clip owner with a degenerate transform, clip left out")
			RenderingServer.canvas_item_set_parent(item, parent)
			RenderingServer.canvas_item_set_transform(item, local)
			RenderingServer.canvas_item_set_self_modulate(item, color_of(nums(t, "mod", 4)))
			if int(clip[0]) != 0 and owner == items.size():
				RenderingServer.canvas_item_set_clip(item, true)
				var cr := rect_of(nums(t, "cliprect", 4))
				RenderingServer.canvas_item_set_custom_rect(item, true, xf.affine_inverse() * cr if absf(xf.determinant()) > 1e-6 else cr)
			var mat := nums(t, "material", 2)
			if int(mat[0]) != 0:
				RenderingServer.canvas_item_set_material(item, material_of(int(mat[0])))
			var group := nums(t, "group", 3)
			if int(group[0]) != 0:
				RenderingServer.canvas_item_set_canvas_group_mode(item, int(group[1]) if int(group[1]) >= 0 else RenderingServer.CANVAS_GROUP_MODE_TRANSPARENT)
				note("canvas group, its children not regrouped")
			var bb := int(nums(t, "backbuffer", 1)[0])
			if bb != 0:
				RenderingServer.canvas_item_set_copy_to_backbuffer(item, true, Rect2() if bb == 2 else rect_of(nums(t, "bbrect", 4)))
			if int(nums(t, "skeleton", 1)[0]) != 0:
				note("skeleton, left out")
			var filter := int(nums(t, "filter", 1)[0])
			if filter != 0:
				RenderingServer.canvas_item_set_default_texture_filter(item, filter)
			var repeat := int(nums(t, "repeat", 1)[0])
			if repeat != 0:
				RenderingServer.canvas_item_set_default_texture_repeat(item, repeat)
			if int(nums(t, "vp", 1)[0]) != 0:
				note("viewport render item, left out")
			items.append(item)
			item_xf.append(xf)
			last_owner = owner
			count += 1
		elif line.begins_with(" ") and item.is_valid():
			add_command(item, t)
	get_window().size = size
	print("REPLAY ", path.get_file(), ": ", calls, " calls, ", count, " items, ", textures.size(), " textures, ", materials.size(), " materials, size ", size)
	if not approx.is_empty():
		print("REPLAY approximations: ", approx)
	for i in 4:
		await RenderingServer.frame_post_draw
	var draws = RenderingServer.get_rendering_info(RenderingServer.RENDERING_INFO_TOTAL_DRAW_CALLS_IN_FRAME)
	var img := get_viewport().get_texture().get_image()
	img.convert(Image.FORMAT_RGBA8)
	var run_name := OS.get_environment("BATCHTEST_NAME")
	var out := FileAccess.open(OS.get_environment("BATCHTEST_OUT") + "/" + run_name + ".raw", FileAccess.WRITE)
	out.store_32(img.get_width())
	out.store_32(img.get_height())
	out.store_buffer(img.get_data())
	out.close()
	print("BATCHTEST ", run_name, " draws ", draws, " size ", img.get_width(), "x", img.get_height(), " renderer ", RenderingServer.get_current_rendering_method())
	var hold := OS.get_environment("BATCHTEST_SECONDS").to_float()
	if hold > 0:
		await get_tree().create_timer(hold).timeout
	get_tree().quit(0)
