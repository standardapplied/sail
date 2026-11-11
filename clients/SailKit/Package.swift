// swift-tools-version: 6.4

import PackageDescription

let strict: [SwiftSetting] = [.enableUpcomingFeature("ExistentialAny")]

let package = Package(
    name: "SailKit",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .library(name: "SailTerminalWire", targets: ["SailTerminalWire"]),
    ],
    targets: [
        .target(name: "SailTerminalWire", swiftSettings: strict),
        .target(name: "SailKitTestSupport", path: "Tests/SailKitTestSupport", swiftSettings: strict),
        .testTarget(
            name: "SailTerminalWireTests",
            dependencies: ["SailTerminalWire", "SailKitTestSupport"],
            swiftSettings: strict
        ),
    ],
    swiftLanguageModes: [.v6]
)
