# Local build on Omarchy / Arch Linux (x86-64)

This build keeps ATL and its Android runtime libraries in `../atl-local`,
relative to this checkout. System libraries come from Arch packages.

Install prerequisites using Omarchy's package helper:

```sh
omarchy pkg add base-devel git meson ninja jdk17-openjdk gtk4 libportal \
  openxr webkitgtk-6.0 libbsd libunwind libelf valgrind icu openssl \
  expat zip lz4 xz libcap alsa-lib ffmpeg libdrm libgudev vulkan-headers \
  vulkan-icd-loader wayland-protocols fontconfig
```

Build and install locally:

```sh
./scripts/build-local.sh
```

The script clones wolfSSL, Bionic Translation, and standalone ART into
sibling directories when absent. Existing source directories are preserved.
It defaults to two compiler jobs; override with `ATL_BUILD_JOBS` if desired.
Set `ATL_PREFIX` to use another absolute installation directory, and use the
same value when launching.

Run an APK:

```sh
./scripts/run-local.sh /absolute/path/to/app.apk
```

With no arguments, the launcher opens a file chooser if `zenity` is installed.

The launcher adds the local shared libraries and Bionic configuration to the
runtime search paths. App data uses ATL's normal per-user data directory.
Android app compatibility is dependent on which APIs ATL implements.
