import CoreGraphics
import Foundation
import Observation
import PloggingDetection
#if canImport(UIKit)
import UIKit
#endif

/// Where the frames come from.
enum SourceKind: String, CaseIterable, Identifiable {
  case glasses
  case mockVideo
  case mockPhoneCamera
  case videoFile

  var id: Self { self }

  var title: String {
    switch self {
    case .glasses: "Meta glasses"
    case .mockVideo: "Simulated glasses: demo video"
    case .mockPhoneCamera: "Simulated glasses: phone camera"
    case .videoFile: "Demo video, no Meta SDK"
    }
  }

  var explanation: String {
    switch self {
    case .glasses:
      "The live camera of the glasses, through the Meta AI app."
    case .mockVideo:
      "The Meta SDK streams the demo video as if it came from Meta Ray-Ban Display glasses."
    case .mockPhoneCamera:
      "The Meta SDK streams the back camera of this phone as if it came from the glasses."
    case .videoFile:
      "Plays the demo video straight into the detector."
    }
  }
}

/// The state of the single screen: picks a source, runs the detector on its frames.
@Observable
@MainActor
final class AppModel {
  var sourceKind: SourceKind = .mockVideo

  private(set) var isRunning = false
  private(set) var status = "Ready"
  private(set) var frame: CGImage?
  private(set) var detections: [Detection] = []
  private(set) var detectionsPerSecond = 0
  private(set) var inferenceMilliseconds = 0.0
  private(set) var glassesRegistered = false
  var errorMessage: String?

  @ObservationIgnored private let glasses: GlassesSource
  @ObservationIgnored private var videoSource: VideoFileFrameSource?
  @ObservationIgnored private var pipeline: DetectionPipeline?
  @ObservationIgnored private var startTask: Task<Void, Never>?
  @ObservationIgnored private var resultTimes: [Date] = []

  init() {
    // The Meta SDK must be configured before anything else touches it.
    GlassesSource.configure()
    glasses = GlassesSource()
    glasses.onStatus = { [weak self] in self?.status = $0 }
    glasses.onError = { [weak self] in self?.errorMessage = $0 }
    glasses.onEnded = { [weak self] in self?.stop(status: "The stream ended") }
    glasses.onRegistrationChange = { [weak self] in self?.glassesRegistered = $0 }
    glasses.observeRegistration()
  }

  /// Glasses mode needs the app to be registered with the Meta AI app first.
  var needsRegistration: Bool {
    sourceKind == .glasses && !glassesRegistered
  }

  // MARK: - Actions

  func start() {
    guard !isRunning else { return }
    isRunning = true
    errorMessage = nil
    status = "Loading the model…"
    setScreenAlwaysOn(true)

    let kind = sourceKind
    startTask = Task { [weak self] in
      guard let self else { return }
      do {
        let pipeline = try await loadPipeline()
        let demoVideo = kind == .glasses || kind == .mockPhoneCamera ? nil : try Self.demoVideoURL()
        try Task.checkCancellation()

        let onFrame: @Sendable (CGImage) -> Void = { [weak self] image in
          self?.receive(image, pipeline: pipeline)
        }

        switch kind {
        case .glasses:
          try await glasses.start(feed: .glasses, onFrame: onFrame)
        case .mockVideo:
          try await glasses.start(feed: .mockVideo(demoVideo!), onFrame: onFrame)
        case .mockPhoneCamera:
          try await glasses.start(feed: .mockPhoneCamera, onFrame: onFrame)
        case .videoFile:
          let source = VideoFileFrameSource(url: demoVideo!)
          videoSource = source
          status = "Playing the demo video"
          source.start(onFrame: onFrame) { [weak self] error in
            Task { @MainActor in
              if let error { self?.fail(error) }
            }
          }
        }
      } catch is CancellationError {
        // Stopped by the user while starting.
      } catch {
        fail(error)
      }
    }
  }

  func stop(status newStatus: String = "Stopped") {
    guard isRunning else { return }
    isRunning = false
    startTask?.cancel()
    startTask = nil
    videoSource?.stop()
    videoSource = nil
    glasses.stop()

    frame = nil
    detections = []
    resultTimes = []
    detectionsPerSecond = 0
    status = newStatus
    setScreenAlwaysOn(false)
  }

  /// Opens the Meta AI app to register this app with the glasses.
  func connectGlasses() {
    Task {
      do {
        try await glasses.register()
      } catch {
        errorMessage = error.localizedDescription
      }
    }
  }

  func disconnectGlasses() {
    stop()
    Task {
      do {
        try await glasses.unregister()
      } catch {
        errorMessage = error.localizedDescription
      }
    }
  }

  /// A link that opened the app: the Meta AI app answers through one.
  func open(_ url: URL) {
    Task {
      if let message = await GlassesSource.handle(url: url) {
        errorMessage = message
      }
    }
  }

  // MARK: - Frames

  /// Called on a background thread for every frame of the source.
  nonisolated private func receive(_ image: CGImage, pipeline: DetectionPipeline) {
    Task { @MainActor in
      guard self.isRunning else { return }
      self.frame = image
    }
    pipeline.submit(image) { [weak self] result in
      Task { @MainActor in self?.show(result) }
    }
  }

  private func show(_ result: DetectionPipeline.Result) {
    guard isRunning else { return }
    detections = result.detections
    inferenceMilliseconds = result.seconds * 1000

    let now = Date()
    resultTimes.append(now)
    resultTimes.removeAll { now.timeIntervalSince($0) > 1 }
    detectionsPerSecond = resultTimes.count
  }

  // MARK: - Private

  private func loadPipeline() async throws -> DetectionPipeline {
    if let pipeline { return pipeline }
    // Xcode compiles App/Resources/Detector.mlpackage into Detector.mlmodelc.
    guard let url = Bundle.main.url(forResource: "Detector", withExtension: "mlmodelc") else {
      throw SetupError.missing("Detector.mlpackage")
    }
    let loaded = DetectionPipeline(detector: try await ObjectDetector.load(modelURL: url))
    pipeline = loaded
    return loaded
  }

  private static func demoVideoURL() throws -> URL {
    guard let url = Bundle.main.url(forResource: "demo", withExtension: "mp4") else {
      throw SetupError.missing("demo.mp4")
    }
    return url
  }

  private func fail(_ error: Error) {
    stop(status: "Failed")
    errorMessage = error.localizedDescription
  }

  private func setScreenAlwaysOn(_ on: Bool) {
    #if canImport(UIKit)
    UIApplication.shared.isIdleTimerDisabled = on
    #endif
  }
}

enum SetupError: LocalizedError {
  case missing(String)

  var errorDescription: String? {
    switch self {
    case .missing(let file):
      "\(file) is not in the app. Run glasses/scripts/setup.sh and build again."
    }
  }
}
