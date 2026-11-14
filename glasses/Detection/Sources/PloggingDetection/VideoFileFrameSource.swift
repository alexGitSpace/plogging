import AVFoundation
import CoreGraphics
import Foundation
import VideoToolbox

extension CGImage {
  /// Makes an image from a decoded video frame.
  public static func make(from pixelBuffer: CVPixelBuffer) -> CGImage? {
    var image: CGImage?
    VTCreateCGImageFromCVPixelBuffer(pixelBuffer, options: nil, imageOut: &image)
    return image
  }
}

/// Plays a video file as if it were a camera: frames arrive at the pace of the video.
/// Used to try the detector without glasses.
public final class VideoFileFrameSource {
  private let url: URL
  private let loop: Bool
  private let realTime: Bool
  private var task: Task<Void, Never>?

  /// - Parameters:
  ///   - loop: start again from the beginning when the video ends.
  ///   - realTime: wait between frames as a camera would. Turn off to read as fast as possible.
  public init(url: URL, loop: Bool = true, realTime: Bool = true) {
    self.url = url
    self.loop = loop
    self.realTime = realTime
  }

  deinit {
    task?.cancel()
  }

  /// Starts reading. `onFrame` and `onFinish` are called on a background thread.
  /// `onFinish` gets an error if the file could not be read, and `nil` when a
  /// video that does not loop reaches its end.
  public func start(
    onFrame: @escaping @Sendable (CGImage) -> Void,
    onFinish: @escaping @Sendable (Error?) -> Void = { _ in }
  ) {
    stop()
    let url = url
    let loop = loop
    let realTime = realTime
    task = Task.detached(priority: .userInitiated) {
      do {
        repeat {
          try await Self.playOnce(url: url, realTime: realTime, onFrame: onFrame)
        } while loop && !Task.isCancelled
        if !Task.isCancelled { onFinish(nil) }
      } catch {
        if !Task.isCancelled { onFinish(error) }
      }
    }
  }

  public func stop() {
    task?.cancel()
    task = nil
  }

  private static func playOnce(
    url: URL,
    realTime: Bool,
    onFrame: @Sendable (CGImage) -> Void
  ) async throws {
    let asset = AVURLAsset(url: url)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
      throw CocoaError(.fileReadCorruptFile, userInfo: [NSLocalizedDescriptionKey: "No video track in \(url.lastPathComponent)"])
    }

    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(
      track: track,
      outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
    )
    output.alwaysCopiesSampleData = false
    reader.add(output)
    guard reader.startReading() else {
      throw reader.error ?? CocoaError(.fileReadUnknown)
    }
    defer { reader.cancelReading() }

    let clock = ContinuousClock()
    let startedAt = clock.now
    var firstTimestamp: CMTime?

    while !Task.isCancelled, let sample = output.copyNextSampleBuffer() {
      guard let pixelBuffer = CMSampleBufferGetImageBuffer(sample) else { continue }

      if realTime {
        let timestamp = CMSampleBufferGetPresentationTimeStamp(sample)
        let first = firstTimestamp ?? timestamp
        firstTimestamp = first
        let offset = CMTimeGetSeconds(CMTimeSubtract(timestamp, first))
        if offset.isFinite, offset > 0 {
          try? await clock.sleep(until: startedAt + .seconds(offset))
        }
      }

      if let image = CGImage.make(from: pixelBuffer) {
        onFrame(image)
      }
    }

    if reader.status == .failed, let error = reader.error {
      throw error
    }
  }
}
