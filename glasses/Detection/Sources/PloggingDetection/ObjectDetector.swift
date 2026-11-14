import CoreGraphics
import CoreML
import Foundation
import Vision

/// Runs a YOLO Core ML model on single frames.
///
/// The model must be exported with non-maximum suppression built in
/// (`nms=True` in Ultralytics), so that Vision returns ready-made boxes.
/// Call `detect` from one thread at a time.
public final class ObjectDetector {
  public enum DetectorError: LocalizedError {
    case notAnObjectDetector

    public var errorDescription: String? {
      "The model does not return object boxes. Export it with nms=True (see scripts/export_model.py)."
    }
  }

  /// Detections below this confidence are dropped.
  public var confidenceThreshold: Float

  private let model: VNCoreMLModel

  /// - Parameter modelURL: a compiled `.mlmodelc` (what Xcode puts in the app bundle)
  ///   or a source `.mlpackage` / `.mlmodel`, which is compiled first.
  public static func load(modelURL: URL, confidenceThreshold: Float = 0.35) async throws -> ObjectDetector {
    let compiledURL: URL
    if modelURL.pathExtension == "mlmodelc" {
      compiledURL = modelURL
    } else {
      compiledURL = try await MLModel.compileModel(at: modelURL)
    }

    let configuration = MLModelConfiguration()
    #if targetEnvironment(simulator)
    // The simulator has no Neural Engine and its GPU path is unreliable.
    configuration.computeUnits = .cpuOnly
    #else
    configuration.computeUnits = .all
    #endif

    let mlModel = try MLModel(contentsOf: compiledURL, configuration: configuration)
    return ObjectDetector(model: try VNCoreMLModel(for: mlModel), confidenceThreshold: confidenceThreshold)
  }

  private init(model: VNCoreMLModel, confidenceThreshold: Float) {
    self.model = model
    self.confidenceThreshold = confidenceThreshold
  }

  public func detect(in image: CGImage) throws -> [Detection] {
    let request = VNCoreMLRequest(model: model)
    // Fit the whole frame into the square model input with bars around it, as in
    // training. Stretching it instead (.scaleFill) distorts the objects: on a 16:9
    // frame YOLO then calls bottles toothbrushes. Vision maps the boxes back to the frame.
    request.imageCropAndScaleOption = .scaleFit

    try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])

    guard let results = request.results else { return [] }
    guard let observations = results as? [VNRecognizedObjectObservation] else {
      if results.isEmpty { return [] }
      throw DetectorError.notAnObjectDetector
    }

    return observations.compactMap { observation in
      guard let best = observation.labels.first, best.confidence >= confidenceThreshold else { return nil }
      // Vision puts the origin in the bottom-left corner; flip it to top-left.
      let box = observation.boundingBox
      return Detection(
        label: best.identifier,
        confidence: best.confidence,
        box: CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
      )
    }
  }
}
