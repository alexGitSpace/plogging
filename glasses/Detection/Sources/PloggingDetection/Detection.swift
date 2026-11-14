import CoreGraphics
import Foundation

/// One object found in a frame.
public struct Detection: Identifiable, Sendable {
  public let id = UUID()
  /// Class name from the model, such as `bottle`.
  public let label: String
  /// 0 to 1.
  public let confidence: Float
  /// Position in the frame: normalised to 0...1, origin in the top-left corner.
  public let box: CGRect

  public init(label: String, confidence: Float, box: CGRect) {
    self.label = label
    self.confidence = confidence
    self.box = box
  }
}
