#!/usr/bin/env bash
# Boots an arm64 Android (redroid) container on an arm64 Linux host and connects adb to it.
# Needs root for binder; the host kernel must ship binder_linux (GitHub's ubuntu-24.04-arm does).
set -euo pipefail
image=${REDROID_IMAGE:-redroid/redroid:14.0.0_64only-latest}
name=${REDROID_NAME:-kestrel-android}
port=${REDROID_PORT:-5555}
width=${REDROID_WIDTH:-2400}
height=${REDROID_HEIGHT:-1080}

sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
# udmabuf: Mesa's CPU Vulkan driver makes its shareable (dma-buf) memory with it, as phones' GPU
# drivers do with their own allocators. Without it gamescope cannot allocate a single texture.
sudo modprobe udmabuf 2>/dev/null || echo "udmabuf: no module on this kernel"
docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" --privileged \
  -p "127.0.0.1:${port}:5555" \
  -v "${REDROID_DATA:-$PWD/redroid-data}:/data" \
  "$image" \
  androidboot.redroid_width="$width" androidboot.redroid_height="$height" \
  androidboot.redroid_dpi=420 androidboot.redroid_fps=60 \
  androidboot.redroid_gpu_mode="${REDROID_GPU_MODE:-guest}" \
  ro.product.model=Kestrel-CI
serial=127.0.0.1:${port}
deadline=$(( $(date +%s) + ${REDROID_BOOT_TIMEOUT:-300} ))
until adb connect "$serial" 2>/dev/null | grep -q connected && \
      [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "redroid did not boot"; docker logs --tail 200 "$name" || true; exit 1
  fi
  sleep 3
done
adb -s "$serial" root >/dev/null 2>&1 || true
sleep 2; adb connect "$serial" >/dev/null
echo "serial=$serial"
adb -s "$serial" shell 'getprop ro.build.version.release; getprop ro.product.cpu.abilist; getconf PAGESIZE; uname -r'
