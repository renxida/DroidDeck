#!/usr/bin/env bash
# Builds proot for the build machine itself (not Android), from the same pinned source and patches
# as build.sh, so host tests (SessionSuspendRealProcessTest) can run a process tree under it.
# Usage: build-host.sh OUTDIR
#   OUTDIR receives proot and loader; run with PROOT_LOADER=OUTDIR/loader.
#   PROOT_SRC=<git checkout of termux/proot> uses that checkout's pinned commit instead of downloading it.
# Needs a C compiler, make and talloc headers (Debian/Ubuntu: libtalloc-dev).
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/source.env"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/proot"
if [[ -n "${PROOT_SRC:-}" ]]; then
  git -C "$PROOT_SRC" cat-file -e "${PROOT_COMMIT}^{commit}"
  git -C "$PROOT_SRC" archive "$PROOT_COMMIT" | tar -x -C "$WORK/proot"
else
  curl -fsSL --connect-timeout 15 --max-time 90 --retry 3 --retry-all-errors --retry-max-time 120 \
    -o "$WORK/proot.tar.gz" "https://github.com/termux/proot/archive/${PROOT_COMMIT}.tar.gz"
  printf '%s  %s\n' "$PROOT_SHA256" "$WORK/proot.tar.gz" | sha256sum -c -
  tar -xzf "$WORK/proot.tar.gz" -C "$WORK/proot" --strip-components=1
fi
cp "$HERE/statx_mnt_id.h" "$WORK/proot/src/tracee/statx_mnt_id.h"
cp "$HERE/profile.h" "$WORK/proot/src/syscall/profile.h"
for patch in "$HERE"/patches/*.patch; do
  patch -d "$WORK/proot" -p1 --forward --quiet < "$patch"
done
# proot's --ashmem-memfd option includes the Android-only header; only its ioctl numbers are used.
mkdir -p "$WORK/inc/linux"
cat > "$WORK/inc/linux/ashmem.h" <<'EOF'
#ifndef _LINUX_ASHMEM_H
#define _LINUX_ASHMEM_H
#include <linux/ioctl.h>
#include <linux/types.h>
#define ASHMEM_NAME_LEN 256
#define ASHMEM_NAME_DEF "dev/ashmem"
#define ASHMEM_NOT_PURGED 0
#define ASHMEM_WAS_PURGED 1
#define ASHMEM_IS_UNPINNED 0
#define ASHMEM_IS_PINNED 1
struct ashmem_pin { __u32 offset; __u32 len; };
#define __ASHMEMIOC 0x77
#define ASHMEM_SET_NAME _IOW(__ASHMEMIOC, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_GET_NAME _IOR(__ASHMEMIOC, 2, char[ASHMEM_NAME_LEN])
#define ASHMEM_SET_SIZE _IOW(__ASHMEMIOC, 3, size_t)
#define ASHMEM_GET_SIZE _IO(__ASHMEMIOC, 4)
#define ASHMEM_SET_PROT_MASK _IOW(__ASHMEMIOC, 5, unsigned long)
#define ASHMEM_GET_PROT_MASK _IO(__ASHMEMIOC, 6)
#define ASHMEM_PIN _IOW(__ASHMEMIOC, 7, struct ashmem_pin)
#define ASHMEM_UNPIN _IOW(__ASHMEMIOC, 8, struct ashmem_pin)
#define ASHMEM_GET_PIN_STATUS _IO(__ASHMEMIOC, 9)
#define ASHMEM_PURGE_ALL_CACHES _IO(__ASHMEMIOC, 10)
#endif
EOF
# GNU ld (the host linker) has no --rosegment; it is an lld option for the Android loader.
sed -i 's/,--rosegment//' "$WORK/proot/src/GNUmakefile"
# On arm64 hosts loader-info.awk needs strtonum, which mawk (the default awk on some runners) lacks.
mkdir -p "$WORK/bin"
if command -v gawk >/dev/null; then ln -s "$(command -v gawk)" "$WORK/bin/awk"; fi
PATH="$WORK/bin:$PATH" make -C "$WORK/proot/src" -j"$(getconf _NPROCESSORS_ONLN)" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$WORK/inc -DARG_MAX=131072 -Wno-error=implicit-function-declaration" \
    HAS_LOADER_32BIT= proot loader/loader
install -m755 "$WORK/proot/src/proot" "$OUTDIR/proot"
install -m755 "$WORK/proot/src/loader/loader" "$OUTDIR/loader"
grep -q '^#define HAVE_SECCOMP_FILTER' "$WORK/proot/src/build.h" || { echo "ERROR: proot was built without seccomp acceleration"; exit 1; }
PROOT_LOADER="$OUTDIR/loader" "$OUTDIR/proot" -r / /bin/true
ls -l "$OUTDIR/proot" "$OUTDIR/loader"
