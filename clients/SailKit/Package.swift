// swift-tools-version: 6.2

import PackageDescription

let strict: [SwiftSetting] = [.enableUpcomingFeature("ExistentialAny")]

let package = Package(
    name: "SailKit",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .library(name: "SailAPI", targets: ["SailAPI"]),
        .library(name: "SailEvents", targets: ["SailEvents"]),
        .library(name: "SailTerminalWire", targets: ["SailTerminalWire"]),
    ],
    targets: [
        .target(name: "SailAPI", swiftSettings: strict),
        .target(name: "SailEvents", dependencies: ["SailAPI"], swiftSettings: strict),
        .target(name: "SailTerminalWire", dependencies: ["SailAPI"], swiftSettings: strict),
        .target(
            name: "SailKitTestSupport",
            dependencies: ["SailAPI"],
            path: "Tests/SailKitTestSupport",
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailAPITests",
            dependencies: ["SailAPI", "SailKitTestSupport"],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailEventsTests",
            dependencies: ["SailEvents", "SailKitTestSupport"],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailTerminalWireTests",
            dependencies: ["SailTerminalWire", "SailKitTestSupport"],
            swiftSettings: strict
        ),
    ],
    swiftLanguageModes: [.v6]
)
