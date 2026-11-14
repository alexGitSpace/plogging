// Runs the detector on a video file from the command line, without an iPhone or glasses:
//
//   swift run detect-video --model ../App/Resources/yolo11n.mlpackage \
//       --video ../App/Resources/demo.mp4 --annotate frame.png
//
// It prints what was found in each frame and can save one frame with the boxes drawn on it.

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import PloggingDetection
import UniformTypeIdentifiers

@main
enum DetectVideo {
  static func main() async {
    guard let modelPath = value(of: "--model"), let videoPath = value(of: "--video") else {
      fail("usage: detect-video --model <model.mlpackage> --video <video.mp4> [--frames 100] [--confidence 0.35] [--annotate out.png]")
    }
    let frameLimit = value(of: "--frames").flatMap { Int($0) } ?? 100
    let confidence = value(of: "--confidence").flatMap { Float($0) } ?? 0.35

    let detector: ObjectDetector
    do {
      detector = try await ObjectDetector.load(
        modelURL: URL(fileURLWithPath: modelPath),
        confidenceThreshold: confidence
      )
    } catch {
      fail("Could not load the model: \(error.localizedDescription)")
    }

    // Reading is not paced, so the detector sees every frame up to the limit.
    let run = Run()
    let source = VideoFileFrameSource(url: URL(fileURLWithPath: videoPath), loop: false, realTime: false)
    await withCheckedContinuation { (finished: CheckedContinuation<Void, Never>) in
      source.start(
        onFrame: { image in
          guard run.frames < frameLimit, run.error == nil else { return }
          let startedAt = Date()
          do {
            let detections = try detector.detect(in: image)
            run.seconds += Date().timeIntervalSince(startedAt)
            run.frames += 1
            for detection in detections { run.counts[detection.label, default: 0] += 1 }
            if detections.count > (run.best?.detections.count ?? 0) { run.best = (image, detections) }

            let summary = detections
              .map { "\($0.label) \(String(format: "%.2f", $0.confidence))" }
              .joined(separator: ", ")
            print("frame \(run.frames) (\(image.width)x\(image.height)): \(summary.isEmpty ? "-" : summary)")
          } catch {
            run.error = error
          }
        },
        onFinish: { error in
          if run.error == nil { run.error = error }
          finished.resume()
        }
      )
    }

    if let error = run.error {
      fail("Failed: \(error.localizedDescription)")
    }
    guard run.frames > 0 else {
      fail("No frames could be read from \(videoPath)")
    }

    let milliseconds = run.seconds / Double(run.frames) * 1000
    print("\n\(run.frames) frames, \(String(format: "%.1f", milliseconds)) ms per frame on average")
    for (label, count) in run.counts.sorted(by: { $0.value > $1.value }) {
      print("  \(label): \(count)")
    }

    if let annotatePath = value(of: "--annotate"), let best = run.best {
      if let image = annotate(best.image, with: best.detections),
         savePNG(image, to: URL(fileURLWithPath: annotatePath)) {
        print("Saved the frame with the most detections to \(annotatePath)")
      } else {
        fail("Could not save \(annotatePath)")
      }
    }
  }
}

/// What the run has seen so far. The frames arrive one after another
/// on a single background thread, so no locking is needed.
final class Run: @unchecked Sendable {
  var frames = 0
  var seconds = 0.0
  var counts: [String: Int] = [:]
  var best: (image: CGImage, detections: [Detection])?
  var error: Error?
}

func value(of flag: String) -> String? {
  let arguments = CommandLine.arguments
  guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
  return arguments[index + 1]
}

func fail(_ message: String) -> Never {
  FileHandle.standardError.write(Data((message + "\n").utf8))
  exit(1)
}

/// Draws the boxes and labels on a copy of the frame.
func annotate(_ image: CGImage, with detections: [Detection]) -> CGImage? {
  let width = image.width
  let height = image.height
  guard let context = CGContext(
    data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
  ) else { return nil }

  context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
  let green = CGColor(red: 0.1, green: 0.9, blue: 0.3, alpha: 1)
  context.setStrokeColor(green)
  context.setLineWidth(max(2, CGFloat(width) / 320))

  for detection in detections {
    // Core Graphics has its origin in the bottom-left corner, the boxes in the top-left.
    let box = CGRect(
      x: detection.box.minX * CGFloat(width),
      y: (1 - detection.box.maxY) * CGFloat(height),
      width: detection.box.width * CGFloat(width),
      height: detection.box.height * CGFloat(height)
    )
    context.stroke(box)

    let text = "\(detection.label) \(Int((detection.confidence * 100).rounded()))%"
    let font = CTFontCreateWithName("Helvetica-Bold" as CFString, max(12, CGFloat(width) / 45), nil)
    let attributes = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: green] as CFDictionary
    guard let attributed = CFAttributedStringCreate(nil, text as CFString, attributes) else { continue }
    context.textPosition = CGPoint(x: box.minX + 3, y: min(box.maxY + 4, CGFloat(height) - 16))
    CTLineDraw(CTLineCreateWithAttributedString(attributed), context)
  }
  return context.makeImage()
}

func savePNG(_ image: CGImage, to url: URL) -> Bool {
  guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    return false
  }
  CGImageDestinationAddImage(destination, image, nil)
  return CGImageDestinationFinalize(destination)
}
