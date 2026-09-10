#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
atl_prefix=${ATL_PREFIX:-"$repo_dir/../atl-local"}
export LD_LIBRARY_PATH="$atl_prefix/lib:$atl_prefix/lib/art:$atl_prefix/lib/java/dex/art/natives:$atl_prefix/lib/java/dex/android_translation_layer/natives${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export XDG_DATA_DIRS="$atl_prefix/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
if [[ ! -x "$atl_prefix/bin/android-translation-layer" ]]; then
    printf 'ATL is not built at %s. Set ATL_PREFIX to the installation directory.\n' "$atl_prefix" >&2
    exit 1
fi
if (( $# == 0 )); then
    if ! command -v zenity >/dev/null; then
        printf 'Usage: %s /path/to/app.apk [ATL options]\n' "$0" >&2
        exit 2
    fi
    apk=$(zenity --file-selection --title='Open Android APK with ATL' --file-filter='Android apps | *.apk') || exit 0
    set -- "$apk"
fi
exec "$atl_prefix/bin/android-translation-layer" "$@"
