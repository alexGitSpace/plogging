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

# 2. The demo video: three bottles on a table, from Intel's sample videos (CC BY 4.0).
#    The simulated glasses of the Meta SDK on iOS play HEVC only, so it is converted.
if [ ! -f "$ios/demo.mp4" ]; then
  echo "Downloading the demo video..."
  curl --fail --location --silent --show-error \
    --output "$work/demo-source.mp4" \
    "https://github.com/intel-iot-devkit/sample-videos/raw/master/bottle-detection.mp4"
  avconvert --source "$work/demo-source.mp4" --output "$ios/demo.mp4" \
    --preset PresetHEVCHighestQuality --replace
fi
cp "$ios/demo.mp4" "$android/demo.mp4"

echo "Ready:"
echo "  $ios/Detector.mlpackage and $ios/demo.mp4"
echo "  $android/detector.onnx and $android/demo.mp4"
