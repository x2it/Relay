#!/usr/bin/env bash
#
# build-libbox.sh — 从 sing-box v1.14.3 源码构建真实 libbox.aar（arm64，androidapi 24）。
#
# 背景：仓库不入库 libbox.aar（原生二进制体积大、且应由源码可复现构建）。
# 本脚本克隆官方 sing-box 指定 tag，使用 SagerNet 维护的 gomobile 绑定工具，
# 编译 experimental/libbox 为 AAR，并复制到 android/app/libs/libbox.aar。
#
# 前置依赖：
#   - JDK 17（JAVA_HOME 指向）
#   - Android SDK（ANDROID_HOME），并已安装 NDK（ANDROID_NDK_HOME 或脚本自动选 ndk/<ver>）
#   - Go 1.23+（脚本会按 sing-box go.mod 自动切换 toolchain）
#
# 低内存环境（本机 3.9GB / 2 CPU）：固定 GOMAXPROCS=1 与 -p=1，单 ABI arm64，
# 默认仅编译 with_utls（足够 SS/VMess/VLESS/Trojan 出站 + TLS 指纹）。
# 如需完整功能（gvisor TUN / QUIC / WireGuard 等），设置 FULL=1，注意内存需求显著上升。
#
# 用法：
#   ./build-libbox.sh                 # 最小 tags（推荐低内存 CI）
#   FULL=1 ./build-libbox.sh          # 完整官方 tags
#   SINGBOX_TAG=v1.14.3 ./build-libbox.sh
set -euo pipefail

# ---------- 可覆盖变量 ----------
SINGBOX_TAG="${SINGBOX_TAG:-v1.14.3}"
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.12}"
TARGET="${TARGET:-android/arm64}"
ANDROID_API="${ANDROID_API:-24}"
# 默认最小 tags；FULL=1 时追加官方全套（gvisor/quic/wireguard/utls/naive/clash_api ...）
TAGS="${TAGS:-with_utls}"
if [ "${FULL:-0}" = "1" ]; then
  TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0"
fi

# ---------- 路径定位（脚本位于 android/scripts/）----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ANDROID_DIR/.." && pwd)"
OUT_DIR="$ANDROID_DIR/app/libs"
WORK_DIR="${WORK_DIR:-$(mktemp -d -t libbox-XXXXXX)}"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------- 环境 ----------
: "${JAVA_HOME:?请设置 JAVA_HOME 指向 JDK17}"
: "${ANDROID_HOME:?请设置 ANDROID_HOME 指向 Android SDK}"
export PATH="$JAVA_HOME/bin:$HOME/go/bin:$PATH"

# 低内存：单线程编译，避免 OOM
export GOMAXPROCS=1
export GOFLAGS="-p=1"
export GOTOOLCHAIN=auto

# 自动选择一个已安装的 NDK（若未显式设置 ANDROID_NDK_HOME）
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$NDK_DIR" ] || { echo "未找到 NDK，请先 sdkmanager \"ndk;26.1.10909125\""; exit 1; }
  export ANDROID_NDK_HOME="$NDK_DIR"
fi

echo "==> 安装 SagerNet gomobile ($GOMOBILE_VERSION)"
GOBIN="$WORK_DIR/gobin" go install "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_VERSION"
GOBIN="$WORK_DIR/gobin" go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VERSION"
export PATH="$WORK_DIR/gobin:$PATH"

echo "==> 克隆 sing-box $SINGBOX_TAG（浅克隆）"
git clone --depth 1 --branch "$SINGBOX_TAG" \
  https://github.com/SagerNet/sing-box.git "$WORK_DIR/sing-box"
cd "$WORK_DIR/sing-box"

# 标准 Go 工具链（未采用 SagerNet 补丁工具链）下，pidfd_android.go 的 //go:linkname
# os.checkPidfdOnce 在 android 链接期会报 "invalid reference"。该符号仅被赋值、从未被调用，
# 这里替换为本地空实现即可（官方补丁工具链无需此步，但本脚本面向 stock 工具链/CI）。
cat > experimental/libbox/pidfd_android.go <<'GOEOF'
package libbox

import "os"

// checkPidfdOnce 仅被赋值、从未被调用；stock 工具链下不做 linkname。
var checkPidfdOnce = func() error {
	return os.ErrInvalid
}
GOEOF

# oomprofile 内部对 runtime/pprof 私有符号的 //go:linkname 在 stock 工具链 android 链接期
# 同样被拒绝；本产品关闭 OOM 报告，替换为安全空实现。
cat > experimental/libbox/internal/oomprofile/linkname.go <<'GOEOF'
//go:build darwin || linux || windows

package oomprofile

import (
	"runtime"
	"unsafe"
)

func runtimeMemProfileInternal(p []memProfileRecord, inuseZero bool) (n int, ok bool) { return 0, false }
func runtimeBlockProfileInternal(p []blockProfileRecord) (n int, ok bool)          { return 0, false }
func runtimeMutexProfileInternal(p []blockProfileRecord) (n int, ok bool)          { return 0, false }
func runtimeThreadCreateInternal(p []stackRecord) (n int, ok bool)                { return 0, false }
func runtimeGoroutineProfileWithLabels(p []stackRecord, labels []unsafe.Pointer) (n int, ok bool) {
	return 0, false
}
func runtimeCyclesPerSecond() int64                                  { return 0 }
func runtimeMakeProfStack() []uintptr                                { return nil }
func runtimeFrameStartLine(f *runtime.Frame) int                     { return 0 }
func runtimeFrameSymbolName(f *runtime.Frame) string                 { return "" }
func runtimeExpandFinalInlineFrame(stk []uintptr) []uintptr         { return stk }
func stdParseProcSelfMaps(data []byte, addMapping func(lo, hi, offset uint64, file, buildID string)) {
}
func stdELFBuildID(file string) (string, error) { return "", nil }
GOEOF

echo "==> gomobile bind（target=$TARGET api=$ANDROID_API tags=$TAGS）"
mkdir -p "$OUT_DIR"
gomobile bind -v \
  -target "$TARGET" \
  -androidapi "$ANDROID_API" \
  -javapkg=io.nekohasekai \
  -libname=box \
  -tags "$TAGS" \
  -o "$OUT_DIR/libbox.aar" \
  ./experimental/libbox

echo "==> 完成：$OUT_DIR/libbox.aar"
ls -lh "$OUT_DIR/libbox.aar"
