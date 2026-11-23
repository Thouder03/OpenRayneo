#!/bin/sh
# Copyright 2026 Tobi1chi
# SPDX-License-Identifier: Apache-2.0
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
voice_dir="$repo_dir/.build/local-voice"
if [ -s "$voice_dir/lib/libsherpa-onnx-c-api.dylib" ] && [ -s "$voice_dir/lib/libonnxruntime.dylib" ]; then exit 0; fi
case $(uname -m) in arm64) voice_arch=arm64 ;; x86_64) voice_arch=x64 ;; *) exit 1 ;; esac
archive="sherpa-onnx-v2.0.1-osx-$voice_arch-shared-no-tts"
mkdir -p "$voice_dir/lib"

target_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/$archive.tar.bz2"
dest="$voice_dir/runtime.tar.bz2"
if [ ! -s "$dest" ]; then
    part="$dest.part"
    rm -f "$part"
    success=0
    mirrors=""
    if [ -n "${OPENRAYNEO_VOICE_MIRROR:-}" ]; then
        mirrors="${OPENRAYNEO_VOICE_MIRROR:-}"
    fi
    for mirror in $mirrors "" "https://ghproxy.net/" "https://gh-proxy.com/" "https://hub.gitmirror.com/"; do
        if [ -n "$mirror" ]; then
            case "$target_url" in
                https://github.com/*) url="${mirror}${target_url}" ;;
                *) continue ;;
            esac
        else
            url="$target_url"
        fi
        if curl -fL --retry 2 --connect-timeout 10 --silent --show-error "$url" > "$part"; then
            if [ -s "$part" ]; then
                mv "$part" "$dest"
                success=1
                break
            fi
        fi
        rm -f "$part"
    done
    if [ "$success" -ne 1 ]; then
        echo "Failed to download $target_url from all sources." >&2
        exit 1
    fi
fi
tar -xjf "$voice_dir/runtime.tar.bz2" -C "$voice_dir"
cp "$voice_dir/$archive/lib/libsherpa-onnx-c-api.dylib" "$voice_dir/lib/"
cp -L "$voice_dir/$archive/lib/libonnxruntime.dylib" "$voice_dir/lib/"
