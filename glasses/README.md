# glasses

Two apps, one for iOS and one for Android, that take the camera stream of Meta Ray-Ban Display glasses and run a YOLO object detector on every frame, on the phone. Later they will send a `sighting` event to the API for each piece of litter ([contract](../contract/README.md)); for now they draw the detected objects on the screen.

They work without glasses too: the Meta SDK can simulate a pair that plays a video or shows the phone camera.

| Path | What it is |
|---|---|
| `App/` | The iOS app: one screen in SwiftUI. `GlassesSource.swift` is the only file that talks to the Meta SDK |
| `Detection/` | A Swift package with the iOS detector. It also builds on a Mac, with a command-line tool to try a model on a video |
| `project.yml` | The [XcodeGen](https://github.com/yonaskolb/XcodeGen) spec that `PloggingGlasses.xcodeproj` is generated from |
| `android/` | The Android app: one screen in Jetpack Compose. `GlassesSource.kt` is the only file that talks to the Meta SDK |
| `scripts/` | `setup.sh` prepares the models and the demo video; `export_model.py` converts YOLO weights for both apps |

The two apps share no code, but they have the same screen, the same four sources and the same file names, so a change in one is easy to repeat in the other.

## Setup

You need a Mac with Python 3, and Xcode 26.4 or newer for iOS or a JDK 17 or newer and the Android SDK for Android.

```bash
glasses/scripts/setup.sh
```

It makes the files that are not in git: TACO YOLO11s (litter classes such as `Bottle`, `Can`, `Cigarette` and `Plastic bag & wrapper`) as `App/Resources/Detector.mlpackage` for iOS and `android/app/src/main/assets/detector.onnx` for Android, and `demo.mp4` (three bottles on a table) for both. The weights stay out of git: `setup.sh` downloads them from Hugging Face.

## Sources

Pick a source at the top of the screen and press Start.

| Source | Needs | What it shows |
|---|---|---|
| Simulated glasses: demo video | Simulator, emulator or phone | The whole Meta SDK path (session, camera, stream) on a video |
| Simulated glasses: phone camera | Phone, or the Android emulator | The same path on the live back camera |
| Demo video, no Meta SDK | Simulator, emulator or phone | The detector alone, if the SDK gets in the way |
| Meta glasses | Phone and glasses | The real thing |

Real glasses need a real phone: simulators and emulators have no Bluetooth and no Meta AI app.

## iOS

```bash
open glasses/PloggingGlasses.xcodeproj
```

The first build downloads the Meta SDK as a Swift package. Choose an iPhone simulator and press Run.

On real glasses:

1. In Xcode, select the PloggingGlasses target, then Signing & Capabilities: choose your team and change the bundle identifier to your own.
2. Pair the glasses with the Meta AI app on the iPhone and turn on **Developer Mode** in Meta AI (see below).
3. Run the app on the iPhone, choose **Meta glasses** and tap **Connect glasses**. Meta AI opens: confirm, and it returns to the app.
4. Put the glasses on and press **Start**. The first time, Meta AI asks to allow the camera.

## Android

Open `glasses/android` in Android Studio, or build from the command line with a phone or a running emulator attached:

```bash
cd glasses/android && ./gradlew installDebug
```

If Gradle cannot find the Android SDK, put its path into `glasses/android/local.properties` as `sdk.dir=/path/to/Android/sdk`.

On real glasses, the steps are the same as on iOS: turn on **Developer Mode** in Meta AI, run the app on the phone, allow **Nearby devices** when Android asks, choose **Meta glasses**, then tap **Connect glasses** and confirm in Meta AI. The live view starts when the app returns registered.

The Android app defaults to Meta glasses and starts the live view automatically once the app is registered. First-time registration and camera permission still require confirmation in Meta AI.

## AI tooling

OpenAI Codex assisted with the Android full-screen viewfinder, orientation-aware HUD, and Meta glasses startup flow.

Give the emulator several CPU cores, or detection crawls: start it with `emulator -avd <name> -cores 4`.

## Developer Mode and keys

To turn on Developer Mode, open the Meta AI app, go to Settings, then App Info, and tap the **App version** number five times. A Developer Mode switch appears: turn it on and confirm. It can turn itself off after a firmware update.

Without it, **Connect glasses** ends in Meta AI with "Error opening link": Meta AI refuses to register an app that has no app ID unless Developer Mode is on.

Developer Mode needs no keys. To share an app through a release channel, create a project in the [Wearables Developer Center](https://wearables.developer.meta.com/) and pass its values from outside git: on iOS as the Xcode build settings `META_APP_ID` and `CLIENT_TOKEN`, on Android as `./gradlew installDebug -Pmwdat_application_id=... -Pmwdat_client_token=...`.

If the stream stays on "Looking for glasses", check Developer Mode, Bluetooth, and that the glasses are unfolded and on your head. A tap on the side of the glasses pauses and resumes the stream.

## Trying a model on a Mac

No phone, glasses or Xcode needed, only the Command Line Tools:

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

This replaces the model of both apps; no app code changes. Add `--only ios` or `--only android` for one of them. The model must be a YOLO detection model that Ultralytics can export with `nms=True`.

## Notes

- Ultralytics YOLO and its weights are under AGPL-3.0, which is why the models are made by a script and not committed to this MIT repository. Check the licence before shipping anything beyond the hackathon demo.
- The glasses stream at 360x640, 24 frames a second, uncompressed. Low resolution survives Bluetooth best and is enough for the detector.
- The frame is fitted into the square model input with bars, not stretched: stretched, YOLO takes bottles for toothbrushes.
- The two apps report different confidences for the same frame (iOS near 100%, Android near 90% on the demo bottles). The boxes and classes agree; the Core ML export scores them differently from the ONNX one.
- iOS: build with code signing on, as Xcode does by default, also for the simulator. In an unsigned build (`CODE_SIGNING_ALLOWED=NO`) the Meta SDK fails to configure and the app crashes at launch.
- iOS: at launch the console fills with "Class ... is implemented in both MWDATMockDevice and MWDATCamera". It comes from inside the Meta SDK and is harmless.
- Android: the model runs on ONNX Runtime with the default CPU backend, about 70 ms a frame on a four-core emulator. The XNNPACK backend was tried and was twice as slow.
- The demo video is from [intel-iot-devkit/sample-videos](https://github.com/intel-iot-devkit/sample-videos), CC BY 4.0.
