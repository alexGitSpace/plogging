// swift-tools-version: 5.9

import PackageDescription

// The detection code lives in a package so that it runs both inside the iOS app
// and from the command line on a Mac (`swift run detect-video ...`).
let package = Package(
  name: "PloggingDetection",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [
    .library(name: "PloggingDetection", targets: ["PloggingDetection"]),
    .executable(name: "detect-video", targets: ["detect-video"]),
  ],
  targets: [
    .target(name: "PloggingDetection"),
    .executableTarget(name: "detect-video", dependencies: ["PloggingDetection"]),
  ]
)
