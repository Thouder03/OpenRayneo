#!/bin/sh
# Copyright 2026 Tobi1chi
# SPDX-License-Identifier: Apache-2.0
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
voice_assets="$repo_dir/android/app/src/main/assets/voice-models"
voice_libs="$repo_dir/android/app/libs"
mkdir -p "$voice_assets" "$voice_libs"
fetch() {
    target_url="$1"
    dest="$2"
    if command -v cygpath >/dev/null 2>&1; then
        dest_win=$(cygpath -m "$dest")
    else
        dest_win="$dest"
    fi
    if [ ! -s "$dest" ] && [ ! -s "$dest_win" ]; then
        part="$dest.part"
        part_win="${dest_win}.part"
        rm -f "$part" "$part_win"
        success=0
        mirrors=""
        if [ -n "${OPENRAYNEO_VOICE_MIRROR:-}" ]; then
            mirrors="${OPENRAYNEO_VOICE_MIRROR:-}"
        fi
        for mirror in $mirrors "" "https://ghproxy.net/" "https://gh-proxy.com/" "https://mirror.ghproxy.com/" "https://hub.gitmirror.com/"; do
            if [ -n "$mirror" ]; then
                case "$target_url" in
                    https://github.com/*) url="${mirror}${target_url}" ;;
                    *) continue ;;
                esac
            else
                url="$target_url"
            fi
            echo "Downloading $(basename "$dest") from $url ..."
            if curl -fL --connect-timeout 5 --retry 1 --silent --show-error "$url" > "$part"; then
                if [ -s "$part" ]; then
                    mv "$part" "$dest"
                    success=1
                    break
                fi
            fi
            rm -f "$part" "$part_win"
        done
        if [ "$success" -ne 1 ]; then
            echo "Failed to download $target_url from all sources." >&2
            exit 1
        fi
    fi
}
if [ "${OPENRAYNEO_VOICE_MODELS_ONLY:-0}" != 1 ]; then
    fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar "$voice_libs/sherpa-onnx-2.0.1.aar"
fi
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx "$voice_assets/campplus-zh-16k.onnx"
fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx "$voice_assets/silero-vad.onnx"
if [ ! -s "$voice_assets/segmentation.int8.onnx" ]; then
    voice_cache="$repo_dir/.build/android-voice-models"
    mkdir -p "$voice_cache"
    fetch https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 "$voice_cache/segmentation.tar.bz2"
    rm -f "$voice_assets/segmentation.int8.onnx.part"
    tar -xOf "$voice_cache/segmentation.tar.bz2" sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx > "$voice_assets/segmentation.int8.onnx.part"
    mv "$voice_assets/segmentation.int8.onnx.part" "$voice_assets/segmentation.int8.onnx"
fi
