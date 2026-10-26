#!/bin/sh
# 取三套第三方桌面 APK 到 vendor/。只从各自官方发布页下载。
#
# 说明：Emotn UI 是闭源免费软件，没有稳定的公开直链，需要自己放一份到
#       vendor/EmotnUI-1.1.0.2.apk（从电视上的应用商店/官网获取）。
set -eu
cd "$(dirname "$0")"
mkdir -p vendor

# GitHub 的 releases API → 取第一条匹配的下载地址
gh_asset() {
    repo="$1"; pattern="$2"; out="$3"
    if [ -f "$out" ]; then echo "exists: $out"; return 0; fi
    url=$(curl -sfL "https://api.github.com/repos/$repo/releases" \
        | sed 's/"browser_download_url": *"/\n/g' \
        | grep -o "https://[^\"]*$pattern[^\"]*" | head -1)
    if [ -z "$url" ]; then
        # 回落：直接拼 github.com 直链
        echo "warn: 无法从 API 解析 $repo 的 $pattern" >&2
        return 1
    fi
    echo "get $url"
    curl -fL "$url" -o "$out"
    echo "  -> $out $(wc -c < "$out") bytes"
}

gh_asset mmaotv/AerialToolbox "CleanYourTV-3.0.0.apk"           vendor/CleanYourTV-3.0.0.apk
gh_asset pengchujin/aerial-tv   "aerial-standard.apk"           vendor/aerial-standard.apk

if [ -f vendor/EmotnUI-1.1.0.2.apk ]; then
    echo "exists: vendor/EmotnUI-1.1.0.2.apk"
else
    echo "missing: vendor/EmotnUI-1.1.0.2.apk（Emotn UI 为闭源软件，请自行放置）" >&2
fi
