#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
work_dir=$(dirname -- "$repo_dir")
atl_prefix=${ATL_PREFIX:-"$work_dir/atl-local"}
jobs=${ATL_BUILD_JOBS:-2}
export PATH="/usr/lib/jvm/java-17-openjdk/bin:$atl_prefix/bin:$PATH"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export CPATH="$atl_prefix/include${CPATH:+:$CPATH}"
export LIBRARY_PATH="$atl_prefix/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
export LD_LIBRARY_PATH="$atl_prefix/lib:$atl_prefix/lib/art${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PKG_CONFIG_PATH="$atl_prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"

# Source directories are kept alongside the ATL checkout.
fetch_source() {
    local dir=$1 url=$2 revision=$3
    if [[ ! -d "$dir" ]]; then
        git clone "$url" "$dir"
        git -C "$dir" checkout "$revision"
    fi
}
fetch_source "$work_dir/atl-wolfssl" https://github.com/wolfSSL/wolfssl.git decea12e223869c8f8f3ab5a53dc90b69f436eb2
fetch_source "$work_dir/atl-bionic" https://gitlab.com/android_translation_layer/bionic_translation.git ef588129f1b505e62de8750793e44aba98817a98
fetch_source "$work_dir/atl-art" https://gitlab.com/android_translation_layer/art_standalone.git 66a5d9079b159e9c5819bfeef4a6fff6a5f32719

(
    cd "$work_dir/atl-wolfssl"
    autoreconf -i
    ./configure --prefix="$atl_prefix" --enable-shared \
        --disable-opensslall --disable-opensslextra \
        --enable-aescbc-length-checks --enable-curve25519 --enable-ed25519 \
        --enable-ed25519-stream --enable-oldtls --enable-base64encode \
        --enable-tlsx --enable-scrypt --disable-examples --enable-crl \
        --enable-jni --enable-sessioncerts
    make -j"$jobs" CFLAGS='-O2 -Wno-error=stringop-truncation'
    make install
)
meson setup --reconfigure "$work_dir/atl-bionic/builddir" "$work_dir/atl-bionic" --prefix="$atl_prefix" --libdir=lib
meson compile -C "$work_dir/atl-bionic/builddir" -j "$jobs"
meson install -C "$work_dir/atl-bionic/builddir"
make -C "$work_dir/atl-art" -j"$jobs" ____PREFIX="$atl_prefix" ____LIBDIR=lib
make -C "$work_dir/atl-art" ____PREFIX="$atl_prefix" ____LIBDIR=lib install
meson setup --reconfigure "$repo_dir/builddir" "$repo_dir" --prefix="$atl_prefix" --libdir=lib
meson compile -C "$repo_dir/builddir" -j "$jobs"
meson install -C "$repo_dir/builddir"
