// swift-tools-version: 6.2

import PackageDescription

let strict: [SwiftSetting] = [.enableUpcomingFeature("ExistentialAny")]

let apple: [Platform] = [.macOS, .iOS]

let package = Package(
    name: "SailKit",
    platforms: [.macOS(.v15), .iOS(.v18)],
    products: [
        .library(name: "SailAPI", targets: ["SailAPI"]),
        .library(name: "SailEvents", targets: ["SailEvents"]),
        .library(name: "SailTerminalWire", targets: ["SailTerminalWire"]),
        .library(name: "SailVT", targets: ["SailVT"]),
        .library(name: "SailTerminal", targets: ["SailTerminal"]),
        .executable(name: "sail-term-probe", targets: ["sail-term-probe"]),
    ],
    targets: [
        .binaryTarget(
            name: "GhosttyVt",
            url: "https://release.files.ghostty.org/libghostty-vt/1.0.1/ghostty-vt-1.0.1.xcframework.zip",
            checksum: "c0f9631277f726154c8f521954d17cdc41aac4626fc0df8cf925a19ee58e829b"
        ),
        .systemLibrary(name: "CGhosttyVT", pkgConfig: "libghostty-vt"),
        .target(name: "SailAPI", swiftSettings: strict),
        .target(name: "SailEvents", dependencies: ["SailAPI"], swiftSettings: strict),
        .target(name: "SailTerminalWire", dependencies: ["SailAPI"], swiftSettings: strict),
        .target(
            name: "SailVT",
            dependencies: [
                .target(name: "GhosttyVt", condition: .when(platforms: apple)),
                .target(name: "CGhosttyVT", condition: .when(platforms: [.linux])),
            ],
            swiftSettings: strict
        ),
        .target(
            name: "SailTerminal",
            dependencies: ["SailAPI", "SailTerminalWire", "SailVT"],
            swiftSettings: strict
        ),
        .executableTarget(
            name: "sail-term-probe",
            dependencies: ["SailAPI", "SailTerminal", "SailVT"],
            swiftSettings: strict
        ),
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
        .testTarget(
            name: "SailVTTests",
            dependencies: [
                "SailVT", "SailKitTestSupport",
                .target(name: "GhosttyVt", condition: .when(platforms: apple)),
                .target(name: "CGhosttyVT", condition: .when(platforms: [.linux])),
            ],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailTerminalTests",
            dependencies: ["SailTerminal", "SailTerminalWire", "SailVT", "sail-term-probe", "SailKitTestSupport"],
            swiftSettings: strict
        ),
    ],
    swiftLanguageModes: [.v6]
)
