#!/bin/bash
# Prepares what git does not hold: the Core ML model and the demo video.
# Run once from anywhere: glasses/scripts/setup.sh
set -euo pipefail

cd "$(dirname "$0")/.."
resources="App/Resources"
work=".build/setup"
mkdir -p "$resources" "$work"

# 1. The model: pre-trained YOLO11n, exported to Core ML.
if [ ! -d "$resources/Detector.mlpackage" ]; then
  echo "Exporting the YOLO model to Core ML..."
  if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
  fi
  # numpy above 2.3 breaks the Core ML converter.
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet ultralytics coremltools "numpy<=2.3.5"
  (cd "$work" && ../../.venv/bin/python ../../scripts/export_model.py)
fi

# 2. The demo video: three bottles on a table, from Intel's sample videos (CC BY 4.0).
#    The simulated glasses of the Meta SDK play HEVC only, so it is converted.
if [ ! -f "$resources/demo.mp4" ]; then
  echo "Downloading the demo video..."
  curl --fail --location --silent --show-error \
    --output "$work/demo-source.mp4" \
    "https://github.com/intel-iot-devkit/sample-videos/raw/master/bottle-detection.mp4"
  avconvert --source "$work/demo-source.mp4" --output "$resources/demo.mp4" \
    --preset PresetHEVCHighestQuality --replace
fi

echo "Ready: $resources/Detector.mlpackage and $resources/demo.mp4"
