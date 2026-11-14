import PloggingDetection
import SwiftUI

struct ContentView: View {
  @Bindable var model: AppModel

  var body: some View {
    VStack(spacing: 12) {
      sourcePicker
      preview
      statusLine
      detectionList
      controls
    }
    .padding()
    .alert(
      "Something went wrong",
      isPresented: Binding(
        get: { model.errorMessage != nil },
        set: { if !$0 { model.errorMessage = nil } }
      )
    ) {
      Button("OK") { model.errorMessage = nil }
    } message: {
      Text(model.errorMessage ?? "")
    }
  }

  private var sourcePicker: some View {
    VStack(alignment: .leading, spacing: 4) {
      Picker("Source", selection: $model.sourceKind) {
        ForEach(SourceKind.allCases) { kind in
          Text(kind.title).tag(kind)
        }
      }
      .pickerStyle(.menu)
      .disabled(model.isRunning)

      Text(model.sourceKind.explanation)
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }

  private var preview: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 12).fill(.black)
      if let frame = model.frame {
        FrameView(frame: frame, detections: model.detections)
      } else {
        Text(model.isRunning ? "Waiting for the first frame…" : "Press Start")
          .foregroundStyle(.white.opacity(0.7))
      }
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .clipShape(RoundedRectangle(cornerRadius: 12))
  }

  private var statusLine: some View {
    HStack {
      Text(model.status)
      Spacer()
      if model.frame != nil {
        Text("\(model.detectionsPerSecond) detections/s · \(model.inferenceMilliseconds, specifier: "%.0f") ms")
          .monospacedDigit()
      }
    }
    .font(.footnote)
    .foregroundStyle(.secondary)
  }

  /// What is in the frame right now, such as "bottle ×2, person".
  private var detectionList: some View {
    let counts = Dictionary(grouping: model.detections, by: \.label).mapValues(\.count)
    let text = counts
      .sorted { $0.key < $1.key }
      .map { $0.value > 1 ? "\($0.key) ×\($0.value)" : $0.key }
      .joined(separator: ", ")
    return Text(text.isEmpty ? " " : text)
      .font(.headline)
      .lineLimit(1)
      .frame(maxWidth: .infinity, alignment: .leading)
  }

  @ViewBuilder
  private var controls: some View {
    if model.needsRegistration {
      Button("Connect glasses") { model.connectGlasses() }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
      Text("Opens the Meta AI app. Turn on Developer Mode there first.")
        .font(.footnote)
        .foregroundStyle(.secondary)
    } else {
      HStack {
        Button(model.isRunning ? "Stop" : "Start") {
          if model.isRunning { model.stop() } else { model.start() }
        }
        .buttonStyle(.borderedProminent)
        .tint(model.isRunning ? .red : .accentColor)
        .controlSize(.large)

        if model.sourceKind == .glasses && !model.isRunning {
          Button("Disconnect") { model.disconnectGlasses() }
            .controlSize(.large)
        }
      }
    }
  }
}

/// One frame with the boxes of the detected objects on top.
struct FrameView: View {
  let frame: CGImage
  let detections: [Detection]

  var body: some View {
    Image(decorative: frame, scale: 1)
      .resizable()
      .aspectRatio(contentMode: .fit)
      .overlay {
        Canvas { context, size in
          for detection in detections {
            let color = Self.color(for: detection.label)
            let box = CGRect(
              x: detection.box.minX * size.width,
              y: detection.box.minY * size.height,
              width: detection.box.width * size.width,
              height: detection.box.height * size.height
            )
            context.stroke(Path(roundedRect: box, cornerRadius: 4), with: .color(color), lineWidth: 2)

            let label = context.resolve(
              Text("\(detection.label) \(Int((detection.confidence * 100).rounded()))%")
                .font(.caption2.bold())
                .foregroundStyle(.black)
            )
            let textSize = label.measure(in: size)
            let tag = CGRect(
              x: box.minX,
              y: max(0, box.minY - textSize.height - 2),
              width: textSize.width + 8,
              height: textSize.height + 2
            )
            context.fill(Path(roundedRect: tag, cornerRadius: 3), with: .color(color))
            context.draw(label, at: CGPoint(x: tag.minX + 4, y: tag.minY + 1), anchor: .topLeading)
          }
        }
      }
  }

  /// The same colour for the same class on every frame.
  private static func color(for label: String) -> Color {
    let seed = label.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) % 360 }
    return Color(hue: Double(seed) / 360, saturation: 0.75, brightness: 1)
  }
}
