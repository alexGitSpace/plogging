#!/bin/bash
# Prepares what git does not hold: the models and the demo video, for both apps.
# Run once from anywhere: glasses/scripts/setup.sh
set -euo pipefail

cd "$(dirname "$0")/.."
ios="App/Resources"
android="android/app/src/main/assets"
work=".build/setup"
mkdir -p "$ios" "$android" "$work"

# 1. The model: TACO YOLO11s (litter classes), exported to Core ML and ONNX.
if [ ! -d "$ios/Detector.mlpackage" ] || [ ! -f "$android/detector.onnx" ]; then
  echo "Exporting the YOLO model..."
  if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
  fi
  # numpy above 2.3 breaks the Core ML converter.
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet ultralytics coremltools onnx onnxslim onnxruntime "numpy<=2.3.5"
  weights="$work/taco-yolo11s.pt"
  expected="1b9c8379aca95efa75ec1f6ac803cabd3ba531b7d4b3f459101bcfd3aaae95ef"
  if [ ! -f "$weights" ] || [ "$(shasum -a 256 "$weights" | awk '{print $1}')" != "$expected" ]; then
    echo "Downloading TACO YOLO11s weights..."
    curl --fail --location --retry 8 --retry-all-errors --retry-delay 2 \
      --output "$weights" \
      "https://huggingface.co/fabiocigaina/TACO-yolo11s/resolve/main/best_model.pt"
    actual="$(shasum -a 256 "$weights" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
      echo "TACO weights checksum mismatch: $actual" >&2
      exit 1
    fi
  fi
  (cd "$work" && ../../.venv/bin/python ../../scripts/export_model.py "$weights")
fi

# 2. The demo video: separate bottles, cans and a carton on a canal bank, from Pexels.
#    The simulated glasses of the Meta SDK on iOS play HEVC only, so it is converted.
source_url="https://videos.pexels.com/video-files/4855724/4855724-hd_1920_1080_25fps.mp4"
expected_source="37afa0e829c92d2bfebdca2769650650fefadfc4eba3f286b3fb138a6ae6943b"
stamp="$work/demo-source.sha256"
if [ ! -f "$ios/demo.mp4" ] || [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$expected_source" ]; then
  echo "Downloading the demo video..."
  curl --fail --location --retry 8 --retry-all-errors --retry-delay 2 \
    --output "$work/demo-source.mp4" \
    "$source_url"
  actual="$(shasum -a 256 "$work/demo-source.mp4" | awk '{print $1}')"
  if [ "$actual" != "$expected_source" ]; then
    echo "Demo video checksum mismatch: $actual" >&2
    exit 1
  fi
  if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -y -loglevel error -i "$work/demo-source.mp4" -map 0:v:0 -an \
      -vf scale=960:540 -c:v libx265 -tag:v hvc1 -pix_fmt yuv420p \
      -crf 23 -preset medium -movflags +faststart -write_tmcd 0 "$ios/demo.mp4"
  else
    avconvert --source "$work/demo-source.mp4" --output "$ios/demo.mp4" \
      --preset PresetHEVCHighestQuality --replace
  fi
  printf '%s\n' "$expected_source" > "$stamp"
fi
cp "$ios/demo.mp4" "$android/demo.mp4"

# 3. MediaPipe hand skeleton, used by the Android app to see a hand closing on litter.
hand="$android/hand_landmarker.task"
expected_hand="fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
if [ ! -f "$hand" ] || [ "$(shasum -a 256 "$hand" | awk '{print $1}')" != "$expected_hand" ]; then
  echo "Downloading the hand landmarker..."
  curl --fail --location --retry 8 --retry-all-errors --retry-delay 2 \
    --output "$hand" \
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
  actual="$(shasum -a 256 "$hand" | awk '{print $1}')"
  if [ "$actual" != "$expected_hand" ]; then
    echo "Hand landmarker checksum mismatch: $actual" >&2
    exit 1
  fi
fi

echo "Ready:"
echo "  $ios/Detector.mlpackage and $ios/demo.mp4"
echo "  $android/detector.onnx, $android/demo.mp4 and $hand"
