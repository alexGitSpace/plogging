import CoreGraphics
import Foundation

/// Feeds camera frames to the detector without falling behind the camera:
/// while one frame is being processed, new frames are skipped.
public final class DetectionPipeline: @unchecked Sendable {
  public struct Result: Sendable {
    public let detections: [Detection]
    /// How long the model took on this frame.
    public let seconds: Double
  }

  private let detector: ObjectDetector
  private let queue = DispatchQueue(label: "plogging.detection", qos: .userInitiated)
  private let lock = NSLock()
  private var isBusy = false

  public init(detector: ObjectDetector) {
    self.detector = detector
  }

  /// Returns at once. `onResult` is called on a background thread,
  /// and not at all for a skipped frame or a frame the model failed on.
  public func submit(_ image: CGImage, onResult: @escaping @Sendable (Result) -> Void) {
    lock.lock()
    let skip = isBusy
    isBusy = true
    lock.unlock()
    if skip { return }

    queue.async { [self] in
      let startedAt = Date()
      let detections = try? detector.detect(in: image)
      let seconds = Date().timeIntervalSince(startedAt)

      lock.lock()
      isBusy = false
      lock.unlock()

      if let detections {
        onResult(Result(detections: detections, seconds: seconds))
      }
    }
  }
}
