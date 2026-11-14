#!/bin/bash
# Prepares what git does not hold: the models and the demo video, for both apps.
# Run once from anywhere: glasses/scripts/setup.sh
set -euo pipefail

cd "$(dirname "$0")/.."
ios="App/Resources"
android="android/app/src/main/assets"
work=".build/setup"
mkdir -p "$ios" "$android" "$work"

# 1. The model: pre-trained YOLO11n, exported to Core ML (iOS) and ONNX (Android).
if [ ! -d "$ios/Detector.mlpackage" ] || [ ! -f "$android/detector.onnx" ]; then
  echo "Exporting the YOLO model..."
  if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
  fi
  # numpy above 2.3 breaks the Core ML converter.
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet ultralytics coremltools onnx onnxslim onnxruntime "numpy<=2.3.5"
  (cd "$work" && ../../.venv/bin/python ../../scripts/export_model.py)
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
