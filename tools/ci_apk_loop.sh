#!/usr/bin/env bash
# Install the newest CI-built APK for a branch, then optionally run a device scenario.
#   tools/ci_apk_loop.sh <branch> [scenario.json] [scenario args...]
# DROIDDECK_CI_REPO picks the repository whose Build APK runs to use (default: this checkout's origin).
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
branch=${1:?usage: tools/ci_apk_loop.sh <branch> [scenario.json] [scenario args...]}
shift
repo=${DROIDDECK_CI_REPO:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
adb_bin=${ADB:-${sdk_dir}/platform-tools/adb}

run_id=$(gh run list -R "${repo}" --workflow build.yml --branch "${branch}" --status success \
    --limit 1 --json databaseId --jq '.[0].databaseId // empty')
[[ -n "${run_id}" ]] || { echo "No successful Build APK run for ${branch} on ${repo}." >&2; exit 1; }
cache=${XDG_CACHE_HOME:-${HOME}/.cache}/droiddeck-ci-apks/${run_id}
if [[ ! -f "${cache}/app-release.apk" ]]; then
    rm -rf "${cache}.part"
    gh run download "${run_id}" -R "${repo}" -n droiddeck-apk -D "${cache}.part"
    apk=$(find "${cache}.part" -name '*.apk' | head -1)
    [[ -n "${apk}" ]] || { echo "Run ${run_id} has no APK in droiddeck-apk." >&2; exit 1; }
    mkdir -p "${cache}" && mv "${apk}" "${cache}/app-release.apk" && rm -rf "${cache}.part"
fi
echo "Run ${run_id} of ${repo}@${branch}: ${cache}/app-release.apk" >&2

serial=$(python3 "${repo_root}/tools/adb_device.py" --adb "${adb_bin}")
if ! out=$("${adb_bin}" -s "${serial}" install -r --no-incremental "${cache}/app-release.apk" 2>&1); then
    echo "${out}" >&2
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"${out}"; then
        echo "The installed DroidDeck has another signer. CI builds use the public testkey; uninstalling the" >&2
        echo "current build deletes its data (Steam sign-in, runtime, games in app storage). Do that by hand." >&2
    fi
    exit 1
fi
echo "Installed on ${serial}." >&2
[[ $# -gt 0 ]] || exit 0
exec python3 "${repo_root}/tools/droiddeck-scenario" "$@" --serial "${serial}"
