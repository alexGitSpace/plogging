"""Exports a YOLO model to Core ML for the app.

    python scripts/export_model.py                 # the pre-trained yolo11n (80 COCO classes)
    python scripts/export_model.py path/to/best.pt  # your own trained weights

The result is always written to App/Resources/Detector.mlpackage, so the app
needs no change when the model is swapped. Non-maximum suppression is built
into the exported model: the app gets ready-made boxes from Vision.
"""

import shutil
import sys
from pathlib import Path

from ultralytics import YOLO

weights = sys.argv[1] if len(sys.argv) > 1 else "yolo11n.pt"
glasses_dir = Path(__file__).resolve().parent.parent
target = glasses_dir / "App" / "Resources" / "Detector.mlpackage"

exported = YOLO(weights).export(format="coreml", nms=True, imgsz=640)

if target.exists():
    shutil.rmtree(target)
target.parent.mkdir(parents=True, exist_ok=True)
shutil.move(exported, target)
print(f"Saved {target}")
