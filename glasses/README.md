# glasses

An iOS app that takes the camera stream of Meta Ray-Ban Display glasses and runs a YOLO object detector on every frame, on the phone. Later it will send a `sighting` event to the API for each piece of litter ([contract](../contract/README.md)); for now it draws the detected objects on the screen.

It works without glasses too: the Meta SDK can simulate a pair that plays a video or shows the phone camera.

| Path | What it is |
|---|---|
| `App/` | The app: one screen in SwiftUI. `GlassesSource.swift` is the only file that talks to the Meta SDK |
| `Detection/` | A Swift package with the detector. It also builds on a Mac, with a command-line tool to try a model on a video |
| `scripts/` | `setup.sh` prepares the model and the demo video; `export_model.py` converts YOLO weights to Core ML |
| `project.yml` | The [XcodeGen](https://github.com/yonaskolb/XcodeGen) spec that `PloggingGlasses.xcodeproj` is generated from |

## Setup

You need a Mac with Xcode 26.4 or newer and Python 3.

```bash
glasses/scripts/setup.sh
```

```bash
open glasses/PloggingGlasses.xcodeproj
```

`setup.sh` makes the two files that are not in git: `App/Resources/Detector.mlpackage` (the pre-trained YOLO11n, 80 everyday classes such as `bottle` and `cup`) and `App/Resources/demo.mp4` (three bottles on a table). The first build downloads the Meta SDK as a Swift package.

## Running

Pick a source at the top of the screen and press Start.

| Source | Needs | What it shows |
|---|---|---|
| Simulated glasses: demo video | Simulator or iPhone | The whole Meta SDK path (session, camera, stream) on a video |
| Simulated glasses: phone camera | iPhone | The same path on the live back camera of the phone |
| Demo video, no Meta SDK | Simulator or iPhone | The detector alone, if the SDK gets in the way |
| Meta glasses | iPhone and glasses | The real thing |

### On real glasses

1. In Xcode, select the PloggingGlasses target, then Signing & Capabilities: choose your team and change the bundle identifier to your own.
2. Pair the glasses with the Meta AI app on the iPhone. In Meta AI, open Settings, your glasses, and turn on **Developer Mode**. It turns itself off after a firmware update.
3. Run the app on the iPhone, choose **Meta glasses** and tap **Connect glasses**. Meta AI opens: confirm, and it returns to the app.
4. Put the glasses on and press **Start**. The first time, Meta AI asks to allow the camera.

Developer Mode needs no keys. To share the app through a release channel, create a project in the [Wearables Developer Center](https://wearables.developer.meta.com/) and set `META_APP_ID` and `CLIENT_TOKEN` as Xcode build settings outside git.

If the stream stays on "Looking for glasses", check Developer Mode, Bluetooth, and that the glasses are unfolded and on your head. A tap on the side of the glasses pauses and resumes the stream.

## Trying a model on a Mac

No iPhone, glasses or Xcode needed, only the Command Line Tools:

```bash
cd glasses/Detection
swift run detect-video --model ../App/Resources/Detector.mlpackage --video ../App/Resources/demo.mp4 --annotate frame.png
```

It prints the detections of each frame and saves the frame with the most detections, boxes drawn, to `frame.png`.

## Another model

```bash
cd glasses
.venv/bin/python scripts/export_model.py path/to/best.pt
```

This replaces `App/Resources/Detector.mlpackage`; the app code does not change. The model must be a YOLO detection model that Ultralytics can export with `nms=True`.

## Notes

- Ultralytics YOLO and its weights are under AGPL-3.0, which is why the model is made by a script and not committed to this MIT repository. Check the licence before shipping anything beyond the hackathon demo.
- Build with code signing on, as Xcode does by default, also for the simulator. In an unsigned build (`CODE_SIGNING_ALLOWED=NO`) the Meta SDK fails to configure and the app crashes at launch.
- At launch the console fills with "Class ... is implemented in both MWDATMockDevice and MWDATCamera". It comes from inside the Meta SDK and is harmless.
- The glasses stream at 360x640, 24 frames a second, as raw frames. Low resolution survives Bluetooth best and is enough for the detector.
- The frame is fitted into the square model input with bars, not stretched: stretched, YOLO takes bottles for toothbrushes.
- The demo video is from [intel-iot-devkit/sample-videos](https://github.com/intel-iot-devkit/sample-videos), CC BY 4.0.
