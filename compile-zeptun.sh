#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
# Build the Zeptun tun2socks engine for Android.
#
# Pinned revision of Noisemux/zeptun. Bump deliberately: upstream PR #4
# (memory-safety fixes) was still open when this was pinned, so re-check its
# state before moving the pin.
ZEPTUN_SHA="5620e57cdbf1a4464567a404adbff7cffd9b4bb9"
# Set magic variables for current file & dir
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Vendored security/correctness fixes from upstream PR #4
# (https://github.com/Noisemux/zeptun/pull/4), applied on top of ZEPTUN_SHA.
# The critical one for us is the netlink byte-order fix: without it the
# UID-range policy rules (per-app routing AND the core's self-exclusion) are
# byte-swapped on ARM and match nothing, which would loop the core's own
# traffic back into the tunnel in root mode. Drop this patch and move the pin
# once upstream merges it.
ZEPTUN_PR4_PATCH="$__dir/zeptun-pr4-security-fixes.patch"
# Matches the Zig line zeptun's own CI uses at pin time.
ZIG_VERSION="0.16.0"
if [[ ! -d $NDK_HOME ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
  exit 1
fi
TMPDIR=$(mktemp -d)
clear_tmp () {
  rm -rf $TMPDIR
}
trap 'echo -e "Aborted, error $? in command: $BASH_COMMAND"; trap ERR; clear_tmp; exit 1' ERR INT

ABIS="armeabi-v7a arm64-v8a x86 x86_64"

# 1) Pinned zeptun sources.
git clone --quiet https://github.com/Noisemux/zeptun.git "$TMPDIR/zeptun"
git -C "$TMPDIR/zeptun" checkout --quiet "$ZEPTUN_SHA"
# 1b) Vendored upstream security/correctness fixes (see ZEPTUN_PR4_PATCH).
git -C "$TMPDIR/zeptun" apply --check "$ZEPTUN_PR4_PATCH"
git -C "$TMPDIR/zeptun" apply "$ZEPTUN_PR4_PATCH"

# 2) Zig toolchain.
curl -fsSL -o "$TMPDIR/zig.tar.xz" \
  "https://ziglang.org/download/${ZIG_VERSION}/zig-x86_64-linux-${ZIG_VERSION}.tar.xz"
mkdir -p "$TMPDIR/zig"
tar -xf "$TMPDIR/zig.tar.xz" -C "$TMPDIR/zig" --strip-components=1
ZIG="$TMPDIR/zig/zig"

pushd "$TMPDIR/zeptun" > /dev/null

# 3) JNI libraries: libzeptun.so (engine) + libzeptun-jni.so (bridge), built by
#    zeptun's own script. Loaded in-process by dev.zeptun.Zeptun for the
#    VpnService zeptun tun mode.
export ANDROID_NDK_HOME="$NDK_HOME"
OUT="$TMPDIR/zig-out-android" ZIG="$ZIG" ABIS="$ABIS" bash scripts/build_android.sh

# 4) Static CLI executables, one per ABI. Run as a separate root process for
#    the Root run mode (creates its own tun, installs policy routing).
for abi in $ABIS; do
  case $abi in
    armeabi-v7a) target=arm-linux-androideabi ;;
    arm64-v8a)   target=aarch64-linux-android ;;
    x86)         target=x86-linux-android ;;
    x86_64)      target=x86_64-linux-android ;;
  esac
  "$ZIG" build -Dtarget="$target" -Doptimize=ReleaseSafe --prefix "$TMPDIR/cli-$abi" > /dev/null
  mkdir -p "$TMPDIR/cli-bin/$abi"
  cp "$TMPDIR/cli-$abi/bin/zeptun" "$TMPDIR/cli-bin/$abi/zeptun"
done

popd > /dev/null

# 5) Stage everything under libs-zeptun/<abi>/. The executable is renamed to
#    lib*.so so the APK installer extracts it into nativeLibraryDir as an
#    executable file (filename distinct from the JNI libraries above; same
#    trick as libhevsockstun.so).
#
#    Note: build_android.sh installs libzeptun.so into zig's default prefix
#    (<repo>/zig-out), NOT into $OUT; only libzeptun-jni.so lands in $OUT.
mkdir -p "$__dir/libs-zeptun"
for abi in $ABIS; do
  mkdir -p "$__dir/libs-zeptun/$abi"
  cp "$TMPDIR/zeptun/zig-out/android/jniLibs/$abi/libzeptun.so" "$__dir/libs-zeptun/$abi/"
  cp "$TMPDIR/zig-out-android/jniLibs/$abi/libzeptun-jni.so" "$__dir/libs-zeptun/$abi/"
  cp "$TMPDIR/cli-bin/$abi/zeptun" "$__dir/libs-zeptun/$abi/libzeptuncli.so"
done

rm -rf $TMPDIR
echo "zeptun: staged libs-zeptun for $ABIS"
