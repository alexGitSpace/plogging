"""Exports a YOLO model for the apps.

    python scripts/export_model.py                  # the pre-trained yolo11n (80 COCO classes)
    python scripts/export_model.py path/to/best.pt  # TACO or your own trained weights
    python scripts/export_model.py best.pt --only android

The results always go to the same two files, so the apps need no change when the
model is swapped:

    App/Resources/Detector.mlpackage              Core ML, for iOS
    android/app/src/main/assets/detector.onnx     ONNX, for Android

Non-maximum suppression is built into both: the apps get ready-made boxes.
"""

import argparse
import shutil
from pathlib import Path

from ultralytics import YOLO

parser = argparse.ArgumentParser()
parser.add_argument("weights", nargs="?", default="yolo11n.pt")
parser.add_argument("--only", choices=["ios", "android"], help="export for one platform")
args = parser.parse_args()

glasses_dir = Path(__file__).resolve().parent.parent
targets = {
    "ios": ("coreml", glasses_dir / "App" / "Resources" / "Detector.mlpackage"),
    "android": ("onnx", glasses_dir / "android" / "app" / "src" / "main" / "assets" / "detector.onnx"),
}

for platform, (export_format, target) in targets.items():
    if args.only and args.only != platform:
        continue
    exported = Path(YOLO(args.weights).export(format=export_format, nms=True, imgsz=640))
    if target.is_dir():
        shutil.rmtree(target)
    elif target.exists():
        target.unlink()
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(exported, target)
    print(f"Saved {target}")
