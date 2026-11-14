// Runs the detector on a video file from the command line, without an iPhone or glasses:
//
//   swift run detect-video --model ../App/Resources/Detector.mlpackage \
//       --video ../App/Resources/demo.mp4 --output glasses.mp4
//
// It prints what was found in each frame. --output writes the whole video with the
// glasses overlay. --annotate saves the frame with the most detections as a PNG.

import AVFoundation
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
      fail("usage: detect-video --model <model.mlpackage> --video <video.mp4> [--frames 100] [--confidence 0.35] [--annotate out.png] [--output out.mp4]")
    }
    let outputPath = value(of: "--output")
    let frameLimit = value(of: "--frames").flatMap(Int.init) ?? (outputPath == nil ? 100 : Int.max)
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

    let run = Run()
    run.fps = await nominalFrameRate(of: URL(fileURLWithPath: videoPath))
    run.outputURL = outputPath.map { URL(fileURLWithPath: $0) }

    let source = VideoFileFrameSource(url: URL(fileURLWithPath: videoPath), loop: false, realTime: false)
    await withCheckedContinuation { (finished: CheckedContinuation<Void, Never>) in
      source.start(
        onFrame: { image in
          autoreleasepool {
            guard run.frames < frameLimit, run.error == nil else { return }
            let startedAt = Date()
            do {
              let detections = try detector.detect(in: image)
              run.seconds += Date().timeIntervalSince(startedAt)
              let time = Double(run.frames) / Double(run.fps)
              run.frames += 1
              for detection in detections { run.counts[detection.label, default: 0] += 1 }
              if detections.count > (run.best?.detections.count ?? 0) {
                run.best = (image, detections)
              }

              if run.outputURL != nil {
                if run.writer == nil {
                  run.writer = try AnnotatedVideoWriter(
                    url: run.outputURL!,
                    width: image.width,
                    height: image.height,
                    fps: run.fps
                  )
                }
                let shown = run.hold.update(detections, time: time)
                let card = run.spotter.update(detections: shown, time: time)
                guard let rendered = render(image, detections: shown, card: card) else {
                  throw WriteError.failed("Could not draw a frame")
                }
                try run.writer?.append(rendered)
              }

              let summary = detections
                .map { "\($0.label) \(String(format: "%.2f", $0.confidence))" }
                .joined(separator: ", ")
              print("frame \(run.frames) (\(image.width)x\(image.height)): \(summary.isEmpty ? "-" : summary)")
            } catch {
              run.error = error
            }
          }
        },
        onFinish: { error in
          if run.error == nil { run.error = error }
          finished.resume()
        }
      )
    }

    if let writer = run.writer {
      do {
        try await writer.finish()
      } catch {
        if run.error == nil { run.error = error }
      }
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
      let card = cardFor(best.detections)
      if let image = render(best.image, detections: best.detections, card: card),
         savePNG(image, to: URL(fileURLWithPath: annotatePath)) {
        print("Saved the frame with the most detections to \(annotatePath)")
      } else {
        fail("Could not save \(annotatePath)")
      }
    }

    if let outputPath {
      print("Saved the glasses view to \(outputPath)")
    }
  }
}

final class Run: @unchecked Sendable {
  var frames = 0
  var seconds = 0.0
  var counts: [String: Int] = [:]
  var best: (image: CGImage, detections: [Detection])?
  var error: Error?
  var fps: Int32 = 30
  var outputURL: URL?
  var writer: AnnotatedVideoWriter?
  let hold = BoxHold()
  let spotter = Spotter()
}

enum WriteError: LocalizedError {
  case failed(String)

  var errorDescription: String? {
    switch self {
    case .failed(let message): return message
    }
  }
}

enum Palette {
  static let leaf = CGColor(red: 196.0 / 255, green: 243.0 / 255, blue: 107.0 / 255, alpha: 1)
  static let mist = CGColor(red: 232.0 / 255, green: 240.0 / 255, blue: 233.0 / 255, alpha: 1)
  static let moss = CGColor(red: 154.0 / 255, green: 170.0 / 255, blue: 158.0 / 255, alpha: 1)
  static let night = CGColor(red: 8.0 / 255, green: 17.0 / 255, blue: 14.0 / 255, alpha: 220.0 / 255)
  static let chip = CGColor(red: 20.0 / 255, green: 32.0 / 255, blue: 26.0 / 255, alpha: 0.92)
  static let black = CGColor(gray: 0, alpha: 1)
  static let white = CGColor(gray: 1, alpha: 1)
}

struct GlassesCard {
  var title: String
  var detail: String
  var box: CGRect?
}

enum Glance {
  case left
  case ahead
  case right
}

func cardFor(_ detections: [Detection]) -> GlassesCard {
  guard let best = detections.max(by: { $0.confidence < $1.confidence }) else {
    return GlassesCard(title: "Plogging", detail: "Looking for litter", box: nil)
  }
  return GlassesCard(
    title: titled(best.label),
    detail: whereLine(sign(for: best.box), count: detections.count),
    box: best.box
  )
}

func titled(_ label: String) -> String {
  guard let first = label.first else { return label }
  return first.uppercased() + label.dropFirst()
}

func sign(for box: CGRect) -> Glance {
  if box.midX < 0.35 { return .left }
  if box.midX > 0.65 { return .right }
  return .ahead
}

func whereLine(_ glance: Glance, count: Int) -> String {
  let place: String
  switch glance {
  case .left: place = "On your left"
  case .right: place = "On your right"
  case .ahead: place = "Straight ahead"
  }
  return count > 1 ? "\(place) · \(count) in view" : place
}

func centerDistance(_ a: CGRect, _ b: CGRect) -> CGFloat {
  let dx = a.midX - b.midX
  let dy = a.midY - b.midY
  return (dx * dx + dy * dy).squareRoot()
}

final class BoxHold {
  private var items: [(detection: Detection, until: Double)] = []

  func update(_ detections: [Detection], time: Double) -> [Detection] {
    items.removeAll { $0.until < time }
    for detection in detections {
      if let index = items.firstIndex(where: {
        $0.detection.label == detection.label && centerDistance($0.detection.box, detection.box) < 0.12
      }) {
        items[index] = (detection, time + 0.4)
      } else {
        items.append((detection, time + 0.4))
      }
    }
    return items.map(\.detection)
  }
}

final class Spotter {
  private struct Shown {
    var detection: Detection
    var glance: Glance
    var count: Int
    var at: Double
  }

  private var target: Detection?
  private var seenAt = -1_000.0
  private var shown: Shown?
  private let hold = 1.5
  private let change = 1.5

  func update(detections: [Detection], time: Double) -> GlassesCard {
    let found = follow(detections)
    if let found {
      let matched = target.map { centerDistance($0.box, found.detection.box) < 0.15 } ?? false
      target = found.detection
      seenAt = time
      let latest = Shown(
        detection: found.detection,
        glance: sign(for: found.detection.box),
        count: detections.count,
        at: time
      )
      if let current = shown {
        if time - current.at > change && differs(latest, current) {
          shown = latest
        } else if matched && found.matched {
          shown = Shown(detection: found.detection, glance: current.glance, count: current.count, at: current.at)
        }
      } else {
        shown = latest
      }
    } else if time - seenAt > hold {
      target = nil
      shown = nil
    }

    guard let shown else {
      return GlassesCard(title: "Plogging", detail: "Looking for litter", box: nil)
    }
    return GlassesCard(
      title: titled(shown.detection.label),
      detail: whereLine(shown.glance, count: shown.count),
      box: shown.detection.box
    )
  }

  private func follow(_ detections: [Detection]) -> (detection: Detection, matched: Bool)? {
    guard !detections.isEmpty else { return nil }
    if let last = target,
       let nearest = detections.min(by: { centerDistance($0.box, last.box) < centerDistance($1.box, last.box) }),
       centerDistance(nearest.box, last.box) < 0.15 {
      return (nearest, true)
    }
    return (detections.max(by: { $0.confidence < $1.confidence })!, false)
  }

  private func differs(_ a: Shown, _ b: Shown) -> Bool {
    a.detection.label != b.detection.label
      || a.glance != b.glance
      || a.count != b.count
      || centerDistance(a.detection.box, b.detection.box) > 0.15
  }
}

func nominalFrameRate(of url: URL) async -> Int32 {
  let asset = AVURLAsset(url: url)
  guard let track = try? await asset.loadTracks(withMediaType: .video).first else { return 30 }
  let rate = (try? await track.load(.nominalFrameRate)) ?? 30
  let rounded = Int32(rate.rounded())
  return rounded > 0 ? rounded : 30
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

func render(_ image: CGImage, detections: [Detection], card: GlassesCard) -> CGImage? {
  let width = image.width
  let height = image.height
  guard let context = CGContext(
    data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
  ) else { return nil }

  context.interpolationQuality = .high
  context.setAllowsAntialiasing(true)
  context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
  let openTop = CGFloat(height) * 0.58
  let floorY = CGFloat(height) - openTop
  context.saveGState()
  context.clip(to: CGRect(x: 0, y: floorY, width: CGFloat(width), height: openTop))
  drawDetections(detections, in: context, width: width, height: height, floorY: floorY)
  context.restoreGState()
  drawLiveChip(in: context, width: width, height: height)
  drawCard(card, frame: image, in: context, width: width, height: height)
  return context.makeImage()
}

func drawDetections(_ detections: [Detection], in context: CGContext, width: Int, height: Int, floorY: CGFloat) {
  let canvas = CGFloat(width)
  let lineWidth = max(3, canvas / 320)
  let open = CGRect(x: 0, y: floorY, width: canvas, height: CGFloat(height) - floorY)
  for detection in detections {
    let box = pixelBox(detection.box, width: width, height: height)
    let visible = box.intersection(open)
    guard box.width > 2, box.height > 2, visible.width > 4, visible.height >= max(8, box.height * 0.35) else { continue }
    let radius = min(8, box.width / 2, box.height / 2)
    let path = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)

    context.setFillColor(Palette.leaf.copy(alpha: 0.18)!)
    context.addPath(path)
    context.fillPath()

    context.setStrokeColor(Palette.leaf.copy(alpha: 0.35)!)
    context.setLineWidth(lineWidth * 2.4)
    context.setLineJoin(.round)
    context.addPath(path)
    context.strokePath()

    context.setStrokeColor(Palette.leaf)
    context.setLineWidth(lineWidth)
    context.addPath(path)
    context.strokePath()

    let text = "\(detection.label) \(Int((detection.confidence * 100).rounded()))%"
    drawTag(text, above: box, in: context, canvasWidth: width, canvasHeight: height, floorY: floorY)
  }
}

func pixelBox(_ box: CGRect, width: Int, height: Int) -> CGRect {
  let w = CGFloat(width)
  let h = CGFloat(height)
  return CGRect(
    x: box.minX * w,
    y: (1 - box.maxY) * h,
    width: box.width * w,
    height: box.height * h
  )
}

func drawTag(_ text: String, above box: CGRect, in context: CGContext, canvasWidth: Int, canvasHeight: Int, floorY: CGFloat) {
  let fontSize = max(16, CGFloat(canvasWidth) / 62)
  let font = CTFontCreateWithName("Helvetica-Bold" as CFString, fontSize, nil)
  let attributes = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: Palette.black] as CFDictionary
  guard let attributed = CFAttributedStringCreate(nil, text as CFString, attributes) else { return }
  let line = CTLineCreateWithAttributedString(attributed)
  var ascent: CGFloat = 0
  var descent: CGFloat = 0
  let textWidth = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
  let textHeight = ascent + descent
  let padX: CGFloat = 7
  let padY: CGFloat = 3
  let tagW = textWidth + padX * 2
  let tagH = textHeight + padY * 2
  var tagX = box.minX
  var tagY = box.maxY + 4
  if tagY < floorY + 3 { tagY = floorY + 4 }
  if tagY + tagH > CGFloat(canvasHeight) - 2 {
    tagY = max(floorY + 4, CGFloat(canvasHeight) - tagH - 2)
  }
  if tagX + tagW > CGFloat(canvasWidth) - 2 {
    tagX = CGFloat(canvasWidth) - tagW - 2
  }
  if tagX < 2 { tagX = 2 }
  let tag = CGRect(x: tagX, y: tagY, width: tagW, height: tagH)
  let radius = min(5, tagH / 2)
  context.setFillColor(Palette.leaf)
  context.addPath(CGPath(roundedRect: tag, cornerWidth: radius, cornerHeight: radius, transform: nil))
  context.fillPath()
  context.textMatrix = .identity
  context.textPosition = CGPoint(x: tagX + padX, y: tagY + (tagH - textHeight) / 2 + descent)
  CTLineDraw(line, context)
}

func drawLiveChip(in context: CGContext, width: Int, height: Int) {
  let margin = CGFloat(width) * 0.03
  let chip = CGRect(x: margin, y: CGFloat(height) - margin - 54, width: 188, height: 54)
  context.setFillColor(Palette.chip)
  context.addPath(CGPath(roundedRect: chip, cornerWidth: 27, cornerHeight: 27, transform: nil))
  context.fillPath()

  let dot = CGRect(x: chip.minX + 14, y: chip.midY - 5, width: 10, height: 10)
  context.setFillColor(Palette.leaf)
  context.fillEllipse(in: dot)

  _ = drawText(
    "PLOGGING",
    in: context,
    fontName: "Helvetica-Bold",
    fontSize: 13,
    color: Palette.mist,
    x: chip.minX + 32,
    top: chip.maxY - 8,
    maxWidth: chip.width - 44
  )
  _ = drawText(
    "LIVE VIEW",
    in: context,
    fontName: "Helvetica-Bold",
    fontSize: 11,
    color: Palette.leaf,
    x: chip.minX + 32,
    top: chip.maxY - 28,
    maxWidth: chip.width - 44
  )
}

func drawCard(_ card: GlassesCard, frame: CGImage, in context: CGContext, width: Int, height: Int) {
  let margin = CGFloat(width) * 0.03
  let panelTop = CGFloat(height) * 0.58
  let panel = CGRect(
    x: margin,
    y: margin,
    width: CGFloat(width) - margin * 2,
    height: CGFloat(height) - margin - panelTop
  )
  context.setFillColor(Palette.night)
  context.addPath(CGPath(roundedRect: panel, cornerWidth: 18, cornerHeight: 18, transform: nil))
  context.fillPath()

  let picture = card.box.flatMap { highlightPicture(frame, box: $0) }
  var textRight = panel.maxX - 16
  if let picture {
    let pictureHeight = panel.height - 28
    let pictureWidth = pictureHeight * CGFloat(picture.width) / CGFloat(picture.height)
    let dest = CGRect(
      x: panel.maxX - 14 - pictureWidth,
      y: panel.minY + 14,
      width: pictureWidth,
      height: pictureHeight
    )
    context.saveGState()
    context.addPath(CGPath(roundedRect: dest, cornerWidth: 12, cornerHeight: 12, transform: nil))
    context.clip()
    context.interpolationQuality = .high
    context.draw(picture, in: dest)
    context.restoreGState()
    context.setStrokeColor(Palette.leaf)
    context.setLineWidth(3)
    context.addPath(CGPath(roundedRect: dest, cornerWidth: 12, cornerHeight: 12, transform: nil))
    context.strokePath()
    textRight = dest.minX - 14
  }

  let textLeft = panel.minX + 18
  let maxText = max(40, textRight - textLeft)
  var cursor = panel.maxY - 16
  cursor = drawText(
    "ON THE GLASSES",
    in: context,
    fontName: "Helvetica-Bold",
    fontSize: CGFloat(width) * 0.026,
    color: Palette.leaf,
    x: textLeft,
    top: cursor,
    maxWidth: maxText
  ) - 10
  cursor = drawText(
    card.title,
    in: context,
    fontName: "Helvetica-Bold",
    fontSize: CGFloat(width) * 0.046,
    color: Palette.white,
    x: textLeft,
    top: cursor,
    maxWidth: maxText
  ) - 8
  _ = drawText(
    card.detail,
    in: context,
    fontName: "Helvetica",
    fontSize: CGFloat(width) * 0.030,
    color: Palette.mist,
    x: textLeft,
    top: cursor,
    maxWidth: maxText
  )
}

func highlightPicture(_ image: CGImage, box: CGRect) -> CGImage? {
  let frameW = CGFloat(image.width)
  let frameH = CGFloat(image.height)
  let boxPx = CGRect(
    x: box.minX * frameW,
    y: box.minY * frameH,
    width: max(box.width * frameW, 2),
    height: max(box.height * frameH, 2)
  )
  let shape: CGFloat = 480.0 / 270.0
  let room = min(max(boxPx.width * 1.8, frameW * 0.5), frameW)
  let cropH = min(max(boxPx.height * 1.8, room / shape), frameH)
  let cropW = min(max(room, cropH * shape), frameW)
  let left = min(max(boxPx.midX - cropW / 2, 0), frameW - cropW)
  let top = min(max(boxPx.midY - cropH / 2, 0), frameH - cropH)
  let bounds = CGRect(x: 0, y: 0, width: frameW, height: frameH)
  let crop = CGRect(x: left, y: top, width: cropW, height: cropH).integral.intersection(bounds)
  guard crop.width >= 2, crop.height >= 2, let cropped = image.cropping(to: crop) else { return nil }

  let pictureW = 480
  let pictureH = 270
  guard let context = CGContext(
    data: nil, width: pictureW, height: pictureH, bitsPerComponent: 8, bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
  ) else { return nil }

  context.setFillColor(Palette.black)
  context.fill(CGRect(x: 0, y: 0, width: pictureW, height: pictureH))

  let scale = min(CGFloat(pictureW) / crop.width, CGFloat(pictureH) / crop.height)
  let destW = crop.width * scale
  let destH = crop.height * scale
  let destX = (CGFloat(pictureW) - destW) / 2
  let destY = (CGFloat(pictureH) - destH) / 2
  context.interpolationQuality = .high
  context.draw(cropped, in: CGRect(x: destX, y: destY, width: destW, height: destH))

  let relX = (boxPx.minX - crop.minX) * scale
  let relY = (boxPx.minY - crop.minY) * scale
  let outline = CGRect(
    x: destX + relX,
    y: destY + destH - relY - boxPx.height * scale,
    width: boxPx.width * scale,
    height: boxPx.height * scale
  ).insetBy(dx: -4, dy: -4)

  context.saveGState()
  context.addRect(CGRect(x: destX, y: destY, width: destW, height: destH))
  context.addPath(CGPath(rect: outline, transform: nil))
  context.clip(using: .evenOdd)
  context.setFillColor(CGColor(gray: 0, alpha: 150.0 / 255))
  context.fill(CGRect(x: destX, y: destY, width: destW, height: destH))
  context.restoreGState()

  let radius = min(10, abs(outline.width) / 2, abs(outline.height) / 2)
  context.setStrokeColor(Palette.leaf)
  context.setLineWidth(5)
  context.setLineJoin(.round)
  context.addPath(CGPath(roundedRect: outline, cornerWidth: radius, cornerHeight: radius, transform: nil))
  context.strokePath()
  return context.makeImage()
}

@discardableResult
func drawText(
  _ text: String,
  in context: CGContext,
  fontName: String,
  fontSize: CGFloat,
  color: CGColor,
  x: CGFloat,
  top: CGFloat,
  maxWidth: CGFloat
) -> CGFloat {
  var size = fontSize
  var line: CTLine
  var ascent: CGFloat = 0
  var descent: CGFloat = 0
  repeat {
    let font = CTFontCreateWithName(fontName as CFString, size, nil)
    let attributes = [kCTFontAttributeName: font, kCTForegroundColorAttributeName: color] as CFDictionary
    let attributed = CFAttributedStringCreate(nil, text as CFString, attributes)!
    line = CTLineCreateWithAttributedString(attributed)
    ascent = 0
    descent = 0
    let width = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
    if width <= maxWidth || size <= 12 { break }
    size -= 1
  } while true

  context.textMatrix = .identity
  context.textPosition = CGPoint(x: x, y: top - ascent)
  CTLineDraw(line, context)
  return top - ascent - descent
}

func savePNG(_ image: CGImage, to url: URL) -> Bool {
  guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    return false
  }
  CGImageDestinationAddImage(destination, image, nil)
  return CGImageDestinationFinalize(destination)
}

final class AnnotatedVideoWriter {
  private let writer: AVAssetWriter
  private let input: AVAssetWriterInput
  private let adaptor: AVAssetWriterInputPixelBufferAdaptor
  private let pool: CVPixelBufferPool
  private let width: Int
  private let height: Int
  private let fps: Int32
  private var frameIndex: Int64 = 0

  init(url: URL, width: Int, height: Int, fps: Int32) throws {
    let evenWidth = width - width % 2
    let evenHeight = height - height % 2
    if FileManager.default.fileExists(atPath: url.path) {
      try FileManager.default.removeItem(at: url)
    }
    writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    let settings: [String: Any] = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: evenWidth,
      AVVideoHeightKey: evenHeight,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: 8_000_000,
        AVVideoExpectedSourceFrameRateKey: fps,
        AVVideoMaxKeyFrameIntervalKey: fps,
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
      ],
    ]
    input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
    input.expectsMediaDataInRealTime = false
    adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
    writer.add(input)

    let poolOptions = [kCVPixelBufferPoolMinimumBufferCountKey: 4] as CFDictionary
    let bufferOptions: [CFString: Any] = [
      kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_32BGRA),
      kCVPixelBufferWidthKey: evenWidth,
      kCVPixelBufferHeightKey: evenHeight,
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true,
      kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
    ]
    var created: CVPixelBufferPool?
    guard CVPixelBufferPoolCreate(nil, poolOptions, bufferOptions as CFDictionary, &created) == kCVReturnSuccess,
          let created else {
      throw WriteError.failed("Could not create a frame buffer")
    }
    pool = created
    self.width = evenWidth
    self.height = evenHeight
    self.fps = fps

    guard writer.startWriting() else {
      throw writer.error ?? WriteError.failed("Could not start writing the video")
    }
    writer.startSession(atSourceTime: .zero)
  }

  func append(_ image: CGImage) throws {
    let deadline = Date().addingTimeInterval(5)
    while !input.isReadyForMoreMediaData {
      if Date() > deadline { throw WriteError.failed("The encoder stopped accepting frames") }
      Thread.sleep(forTimeInterval: 0.002)
    }

    var pixelBuffer: CVPixelBuffer?
    var status = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
    var tries = 0
    while (status != kCVReturnSuccess || pixelBuffer == nil) && tries < 100 {
      Thread.sleep(forTimeInterval: 0.005)
      status = CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
      tries += 1
    }
    guard let pixelBuffer else { throw WriteError.failed("Could not allocate a frame") }

    CVPixelBufferLockBaseAddress(pixelBuffer, [])
    guard let base = CVPixelBufferGetBaseAddress(pixelBuffer),
          let context = CGContext(
            data: base,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
          ) else {
      CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
      throw WriteError.failed("Could not draw into a frame")
    }
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

    let time = CMTime(value: frameIndex, timescale: fps)
    frameIndex += 1
    guard adaptor.append(pixelBuffer, withPresentationTime: time) else {
      throw writer.error ?? WriteError.failed("Could not append a frame")
    }
  }

  func finish() async throws {
    input.markAsFinished()
    await writer.finishWriting()
    if writer.status != .completed {
      throw writer.error ?? WriteError.failed("Could not finish the video")
    }
  }
}
