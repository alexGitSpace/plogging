import SwiftUI

@main
struct PloggingGlassesApp: App {
  @State private var model = AppModel()

  var body: some Scene {
    WindowGroup {
      ContentView(model: model)
        // The Meta AI app answers registration and permission requests with a link.
        .onOpenURL { model.open($0) }
    }
  }
}
