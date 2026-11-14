import AVFoundation
import CoreGraphics
import Foundation
import MWDATCamera
import MWDATCore
import MWDATMockDevice
import PloggingDetection
import UIKit

/// The camera of the Meta glasses, through the Meta Wearables Device Access Toolkit.
///
/// This is the only file that talks to the Meta SDK. It can also pair simulated
/// glasses (the SDK's MockDeviceKit), which go through exactly the same session
/// and stream code as real ones.
@MainActor
final class GlassesSource {
  enum Feed {
    /// Real glasses, connected through the Meta AI app.
    case glasses
    /// Simulated glasses that play a video file (HEVC).
    case mockVideo(URL)
    /// Simulated glasses that show what the back camera of the phone sees.
    case mockPhoneCamera
  }

  enum GlassesError: LocalizedError {
    case notRegistered
    case cameraPermissionDenied
    case phoneCameraDenied
    case sessionEnded
    case noCamera

    var errorDescription: String? {
      switch self {
      case .notRegistered: "Connect the glasses first: tap Connect glasses and confirm in the Meta AI app."
      case .cameraPermissionDenied: "The camera of the glasses was not allowed in the Meta AI app."
      case .phoneCameraDenied: "Allow camera access for this app in Settings."
      case .sessionEnded: "The session with the glasses ended before the camera started."
      case .noCamera: "Could not open the camera of the glasses. Try again."
      }
    }
  }

  /// A short text for the status line.
  var onStatus: (String) -> Void = { _ in }
  /// An error from the session or the stream after it has started.
  var onError: (String) -> Void = { _ in }
  /// The stream stopped by itself: the glasses were folded, went out of range, or the video ended.
  var onEnded: () -> Void = {}
  /// Whether this app is registered with the Meta AI app.
  var onRegistrationChange: (Bool) -> Void = { _ in }

  private var session: DeviceSession?
  private var camera: MWDATCamera.Camera?
  private var mockGlasses: (any MockGlasses)?
  private var streamHasStarted = false
  private let tokens = ListenerTokenBag()
  private var registrationTask: Task<Void, Never>?

  // MARK: - App setup

  /// Call once at launch, before anything else in this class.
  static func configure() {
    do {
      try Wearables.configure()
    } catch {
      NSLog("[PloggingGlasses] Wearables.configure failed: \(error)")
    }
  }

  /// Passes a callback from the Meta AI app (registration, permissions) to the SDK.
  /// Returns an error text if the SDK rejected it.
  static func handle(url: URL) async -> String? {
    guard
      let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
      components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
    else { return nil }
    do {
      _ = try await Wearables.shared.handleUrl(url)
      return nil
    } catch {
      return error.localizedDescription
    }
  }

  // MARK: - Registration

  var isRegistered: Bool {
    Wearables.shared.registrationState == .registered
  }

  func observeRegistration() {
    registrationTask?.cancel()
    onRegistrationChange(isRegistered)
    registrationTask = Task { [weak self] in
      for await state in Wearables.shared.registrationStateStream() {
        self?.onRegistrationChange(state == .registered)
      }
    }
  }

  /// Opens the Meta AI app, where the user confirms this app. The answer comes back through `handle(url:)`.
  func register() async throws {
    try await Wearables.shared.startRegistration()
  }

  func unregister() async throws {
    try await Wearables.shared.startUnregistration()
  }

  // MARK: - Streaming

  /// Connects and starts the camera. Returns once frames are requested.
  /// `onFrame` is called on a background thread for every frame.
  func start(feed: Feed, onFrame: @escaping @Sendable (CGImage) -> Void) async throws {
    stop()

    switch feed {
    case .glasses:
      guard isRegistered else { throw GlassesError.notRegistered }
    case .mockVideo(let url):
      try pairMockGlasses { $0.setCameraFeed(fileURL: url) }
    case .mockPhoneCamera:
      guard await AVCaptureDevice.requestAccess(for: .video) else { throw GlassesError.phoneCameraDenied }
      try pairMockGlasses { $0.setCameraFeed(cameraFacing: .back) }
    }

    // 1. Wait until some glasses are powered on, unfolded and in range.
    onStatus("Looking for glasses…")
    let wearables = Wearables.shared
    let selector = AutoDeviceSelector(wearables: wearables)
    for await device in selector.activeDeviceStream() where device != nil { break }
    try Task.checkCancellation()

    // 2. Open a session with them.
    onStatus("Connecting to the glasses…")
    let session = try wearables.createSession(deviceSelector: selector)
    self.session = session
    session.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.onError(error.localizedDescription) }
    }.store(in: tokens)
    try session.start()
    for await state in session.stateStream() {
      if state == .started { break }
      if state == .stopped { throw GlassesError.sessionEnded }
    }
    try Task.checkCancellation()

    // 3. Ask for the camera. The first time this switches to the Meta AI app.
    if try await wearables.checkPermissionStatus(.camera) != .granted {
      onStatus("Allow the camera in the Meta AI app…")
      guard try await wearables.requestPermission(.camera) == .granted else {
        throw GlassesError.cameraPermissionDenied
      }
    }
    try Task.checkCancellation()

    // 4. Start the camera stream. Raw frames need no decoding; low resolution
    //    (360x640) is enough for the detector and survives Bluetooth best.
    let configuration = StreamConfiguration(videoCodec: .raw, resolution: .low, frameRate: 24)
    guard let camera = try session.addCamera(config: configuration) else {
      throw GlassesError.noCamera
    }
    self.camera = camera

    camera.stream.videoFramePublisher.listen { frame in
      if let image = Self.image(from: frame) { onFrame(image) }
    }.store(in: tokens)
    camera.stream.statePublisher.listen { [weak self] state in
      Task { @MainActor in self?.handle(state) }
    }.store(in: tokens)
    camera.stream.errorPublisher.listen { [weak self] error in
      Task { @MainActor in self?.onError(error.localizedDescription) }
    }.store(in: tokens)

    onStatus("Starting the camera…")
    camera.stream.start()
  }

  func stop() {
    tokens.clear()
    streamHasStarted = false
    camera?.stop()
    camera = nil
    session?.stop()
    session = nil

    mockGlasses = nil
    if MockDeviceKit.shared.isEnabled {
      // Back to real glasses: the mock kit replaces them while it is enabled.
      MockDeviceKit.shared.disable()
    }
  }

  // MARK: - Private

  private func handle(_ state: StreamState) {
    guard camera != nil else { return }
    switch state {
    case .waitingForDevice: onStatus("Waiting for the glasses…")
    case .starting: onStatus("Starting the camera…")
    case .streaming: onStatus("Streaming from the glasses")
    case .paused: onStatus("Paused. Tap the side of the glasses to resume")
    case .stopping: onStatus("Stopping…")
    case .stopped:
      // A stream that has not started yet also reports `stopped`: that is not the end.
      if streamHasStarted { onEnded() }
      return
    }
    streamHasStarted = true
  }

  private func pairMockGlasses(setFeed: (any MockCameraKit) -> Void) throws {
    let kit = MockDeviceKit.shared
    // Starts registered and with the camera allowed, so the Meta AI app is not needed.
    kit.enable()
    let glasses = try kit.pairGlasses(model: .metaRayBanDisplay)
    mockGlasses = glasses
    setFeed(glasses.services.camera)
    // Glasses become available once they are on, unfolded and worn.
    glasses.powerOn()
    glasses.unfold()
    glasses.don()
  }

  nonisolated private static func image(from frame: VideoFrame) -> CGImage? {
    if let pixelBuffer = CMSampleBufferGetImageBuffer(frame.sampleBuffer),
       let image = CGImage.make(from: pixelBuffer) {
      return image
    }
    return frame.makeUIImage()?.cgImage
  }
}
