#!/usr/bin/env python3
"""Save the Vulkan pipeline cache once pipeline creation goes quiet, and count what pipelines and shaders cost.

    rd_pipeline_cache_save.py GODOT_SRC

Stock RenderingDevice writes user://vulkan/pipelines.*.cache only when the cache has grown by
rendering/rendering_device/pipeline_cache/save_chunk_size_mb (3 MB) since the last write, or when the engine
closes. The native wrapper stops the engine with SIGKILL (droiddeck-native-godot run(): killpg 9 on every
session stop), so the close path never runs, and a 2D game's cache rarely grows 3 MB in one session: the
pipelines the menu and a fight need are compiled again on every launch. Stock also asks the driver for the
cache's size (vkGetPipelineCacheData with no buffer) after every single pipeline it creates.

Two switches, both off unless set:

  DROIDDECK_NATIVE_PIPELINE_CACHE_QUIET_MS=<ms>  no size query per pipeline; instead, once no pipeline has
                                                 been made for <ms>, the cache is written (on a worker thread,
                                                 as stock does) if it grew at all. FileAccess writes a .tmp
                                                 and renames it, so a kill mid-write leaves the old file.
  DROIDDECK_NATIVE_PIPELINE_CENSUS=1             logs, every 2 s while anything new was made, how many render
                                                 and compute pipelines and shader modules were created since
                                                 the engine started and the time spent in the driver for them
                                                 (and the slowest pipeline), plus the startup cache size and
                                                 each save. Changes nothing.

Fails unless every anchor is found exactly once.
"""
import os
import sys

MARK = "// Kestrel (rd_pipeline_cache_save.py)"

INCLUDE = '#include "rendering_device.h"\n'

HELPERS = INCLUDE + """
""" + MARK + """: pipeline cache saved when creation goes quiet, and a pipeline/shader census.
#include <atomic>
#include <stdlib.h>

static int64_t droiddeck_pc_quiet_ms() {
	static const int64_t v = [] {
		const char *e = getenv("DROIDDECK_NATIVE_PIPELINE_CACHE_QUIET_MS");
		return e ? int64_t(atoll(e)) : int64_t(0);
	}();
	return v;
}

static bool droiddeck_pc_census() {
	static const bool v = [] {
		const char *e = getenv("DROIDDECK_NATIVE_PIPELINE_CENSUS");
		return e && e[0] == '1';
	}();
	return v;
}

static std::atomic<bool> droiddeck_pc_dirty{ false };
static std::atomic<uint64_t> droiddeck_pc_since{ 0 };
static bool droiddeck_pc_flushing = false;
static std::atomic<uint32_t> droiddeck_pc_pipelines{ 0 };
static std::atomic<uint64_t> droiddeck_pc_pipeline_us{ 0 };
static std::atomic<uint64_t> droiddeck_pc_pipeline_max_us{ 0 };
static std::atomic<uint32_t> droiddeck_pc_shaders{ 0 };
static std::atomic<uint64_t> droiddeck_pc_shader_us{ 0 };

static uint64_t droiddeck_pc_now_us() {
	return (droiddeck_pc_census() || droiddeck_pc_quiet_ms() > 0) ? OS::get_singleton()->get_ticks_usec() : 0;
}

static void droiddeck_pc_count_pipeline(uint64_t p_start_us) {
	if (!p_start_us) {
		return;
	}
	const uint64_t us = OS::get_singleton()->get_ticks_usec() - p_start_us;
	droiddeck_pc_pipelines.fetch_add(1);
	droiddeck_pc_pipeline_us.fetch_add(us);
	uint64_t prev = droiddeck_pc_pipeline_max_us.load();
	while (us > prev && !droiddeck_pc_pipeline_max_us.compare_exchange_weak(prev, us)) {
	}
}

static void droiddeck_pc_count_shader(uint64_t p_start_us) {
	if (!p_start_us) {
		return;
	}
	droiddeck_pc_shaders.fetch_add(1);
	droiddeck_pc_shader_us.fetch_add(OS::get_singleton()->get_ticks_usec() - p_start_us);
}

// Once a frame on the render thread: the census line every 2 s while anything new was made.
static void droiddeck_pc_census_frame() {
	if (!droiddeck_pc_census()) {
		return;
	}
	static uint64_t last_ms = 0;
	static uint32_t last_pipelines = UINT32_MAX;
	static uint32_t last_shaders = UINT32_MAX;
	const uint64_t now = OS::get_singleton()->get_ticks_msec();
	if (now - last_ms < 2000) {
		return;
	}
	last_ms = now;
	const uint32_t p = droiddeck_pc_pipelines.load();
	const uint32_t s = droiddeck_pc_shaders.load();
	if (p == last_pipelines && s == last_shaders) {
		return;
	}
	last_pipelines = p;
	last_shaders = s;
	print_line(vformat("native_compat: pipeline census at %.1f s: %d pipelines in %.0f ms (slowest %.1f ms), %d shaders in %.0f ms",
			now / 1000.0, p, droiddeck_pc_pipeline_us.load() / 1000.0, droiddeck_pc_pipeline_max_us.load() / 1000.0, s,
			droiddeck_pc_shader_us.load() / 1000.0));
}
"""

SHADER = """	RDD::ShaderID shader_id = driver->shader_create_from_container(shader_container, driver_immutable_samplers);
"""
SHADER_NEW = """	const uint64_t droiddeck_t0 = droiddeck_pc_now_us(); """ + MARK + """
	RDD::ShaderID shader_id = driver->shader_create_from_container(shader_container, driver_immutable_samplers);
	droiddeck_pc_count_shader(droiddeck_t0);
"""

RENDER = """	RenderPipeline pipeline;
	pipeline.driver_id = driver->render_pipeline_create(
"""
RENDER_NEW = """	RenderPipeline pipeline;
	const uint64_t droiddeck_t0 = droiddeck_pc_now_us(); """ + MARK + """
	pipeline.driver_id = driver->render_pipeline_create(
"""
RENDER_END = """			p_for_render_pass,
			p_specialization_constants);
	ERR_FAIL_COND_V(!pipeline.driver_id, RID());
"""
RENDER_END_NEW = """			p_for_render_pass,
			p_specialization_constants);
	ERR_FAIL_COND_V(!pipeline.driver_id, RID());
	droiddeck_pc_count_pipeline(droiddeck_t0);
"""

COMPUTE = """	ComputePipeline pipeline;
	pipeline.driver_id = driver->compute_pipeline_create(shader->driver_id, p_specialization_constants);
	ERR_FAIL_COND_V(!pipeline.driver_id, RID());
"""
COMPUTE_NEW = """	ComputePipeline pipeline;
	const uint64_t droiddeck_t0 = droiddeck_pc_now_us(); """ + MARK + """
	pipeline.driver_id = driver->compute_pipeline_create(shader->driver_id, p_specialization_constants);
	ERR_FAIL_COND_V(!pipeline.driver_id, RID());
	droiddeck_pc_count_pipeline(droiddeck_t0);
"""

UPDATE = """void RenderingDevice::_update_pipeline_cache(bool p_closing) {
	_THREAD_SAFE_METHOD_
"""
UPDATE_NEW = UPDATE + """
	""" + MARK + """: with a quiet time set, a new pipeline only marks the cache dirty; swap_buffers saves it later.
	if (!p_closing && !droiddeck_pc_flushing && droiddeck_pc_quiet_ms() > 0) {
		droiddeck_pc_since.store(OS::get_singleton()->get_ticks_msec());
		droiddeck_pc_dirty.store(true);
		return;
	}
"""

BUSY = """				// We can't save until the currently running save is done. We'll retry next time; worst case, we'll save when exiting.
"""
BUSY_NEW = """				droiddeck_pc_dirty.store(true); """ + MARK + """: try again next frame.
""" + BUSY

CHUNK = """			must_save = difference > 0 && difference / (1024.0f * 1024.0f) >= save_interval;
"""
CHUNK_NEW = """			must_save = difference > 0 && (droiddeck_pc_flushing || difference / (1024.0f * 1024.0f) >= save_interval); """ + MARK + """
"""

SWAP = """void RenderingDevice::swap_buffers(bool p_present) {
	ERR_RENDER_THREAD_GUARD();
"""
SWAP_NEW = SWAP + """
	""" + MARK + """: save the pipeline cache once creation has been quiet for the set time.
	droiddeck_pc_census_frame();
	if (pipeline_cache_enabled && droiddeck_pc_quiet_ms() > 0 && droiddeck_pc_dirty.load() &&
			OS::get_singleton()->get_ticks_msec() - droiddeck_pc_since.load() >= uint64_t(droiddeck_pc_quiet_ms())) {
		droiddeck_pc_dirty.store(false);
		const size_t before = pipeline_cache_size;
		droiddeck_pc_flushing = true;
		_update_pipeline_cache();
		droiddeck_pc_flushing = false;
		if (pipeline_cache_size != before) {
			print_line(vformat("native_compat: pipeline cache save at %.1f s: %d KiB (was %d KiB)", OS::get_singleton()->get_ticks_msec() / 1000.0,
					int(pipeline_cache_size / 1024), int(before / 1024)));
		}
	}
"""

STARTUP = """			print_verbose(vformat("Startup PSO cache (%.1f MiB)", pipeline_cache_size / (1024.0f * 1024.0f)));
"""
STARTUP_NEW = STARTUP + """			if (droiddeck_pc_census() || droiddeck_pc_quiet_ms() > 0) { """ + MARK + """
				print_line(vformat("native_compat: pipeline cache at startup: %d KiB from %s", int(pipeline_cache_size / 1024), pipeline_cache_file_path));
			}
"""

EDITS = [
    ("include", INCLUDE, HELPERS),
    ("shader create", SHADER, SHADER_NEW),
    ("render pipeline create", RENDER, RENDER_NEW),
    ("render pipeline end", RENDER_END, RENDER_END_NEW),
    ("compute pipeline create", COMPUTE, COMPUTE_NEW),
    ("_update_pipeline_cache", UPDATE, UPDATE_NEW),
    ("save in progress", BUSY, BUSY_NEW),
    ("save chunk", CHUNK, CHUNK_NEW),
    ("swap_buffers", SWAP, SWAP_NEW),
    ("startup cache", STARTUP, STARTUP_NEW),
]


def patch(source):
    if MARK in source:
        raise SystemExit("rd_pipeline_cache_save: already applied")
    for name, old, new in EDITS:
        if source.count(old) != 1:
            raise SystemExit(f"rd_pipeline_cache_save: expected the {name} anchor exactly once")
        source = source.replace(old, new)
    return source


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    path = os.path.join(sys.argv[1], "servers", "rendering", "rendering_device.cpp")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    with open(path, "w", encoding="utf-8") as f:
        f.write(patch(src))
    print("rd_pipeline_cache_save: patched", path)


if __name__ == "__main__":
    main()
