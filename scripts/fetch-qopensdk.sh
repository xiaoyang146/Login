#!/usr/bin/env bash
# 下载并解包腾讯 QQ 互联官方 Android SDK，取出 open_sdk_*.jar 放到 android/app/libs/。
#
# 为什么要这个脚本：3.5.12 之后官方不再提供 Maven 依赖，只能从 QQ互联「SDK 下载」页拿 zip。
# 仓库里已经内置了 3.5.19 的 jar，本脚本用于「jar 缺失」或「升级 SDK」的场景。
#
# 用法:
#   bash scripts/fetch-qopensdk.sh                  # 缺 jar 时才下载
#   bash scripts/fetch-qopensdk.sh --force          # 强制重新下载
#   bash scripts/fetch-qopensdk.sh --url=<zip 地址>  # 指定其它版本
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/android/app/libs"
JAR_NAME="open_sdk_3.5.19_r9483ffc7_lite.jar"

# QQ互联官方 SDK 下载页给出的 3.5.19 直链
DEFAULT_URL="https://tangram-1251316161.file.myqcloud.com/files/20260414/143310b667ece8922594fbe866dabdef.zip"

URL="$DEFAULT_URL"
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --url=*) URL="${arg#--url=}" ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

mkdir -p "$LIBS"

if [ "$FORCE" -eq 0 ] && ls "$LIBS"/open_sdk_*.jar >/dev/null 2>&1; then
  echo ">> 已存在 SDK jar，跳过下载:"
  ls -la "$LIBS"/open_sdk_*.jar
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo ">> 下载 QQ 互联 Android SDK: $URL"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$URL" -o "$TMP/sdk.zip"
else
  wget -q "$URL" -O "$TMP/sdk.zip"
fi
echo ">> 解包 ..."
unzip -o -q "$TMP/sdk.zip" -d "$TMP/sdk"

FOUND="$(find "$TMP/sdk" -name 'open_sdk_*.jar' -o -name 'open-sdk.jar' | head -n 1)"
if [ -z "$FOUND" ]; then
  echo "!! 压缩包里没找到 open_sdk_*.jar，内容如下：" >&2
  find "$TMP/sdk" -type f >&2
  exit 1
fi

cp "$FOUND" "$LIBS/$JAR_NAME"
echo ">> 已安装: $LIBS/$JAR_NAME"
ls -la "$LIBS"
