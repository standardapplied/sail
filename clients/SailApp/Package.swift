// swift-tools-version: 6.2

import PackageDescription

let strict: [SwiftSetting] = [.enableUpcomingFeature("ExistentialAny")]

func kit(_ name: String) -> Target.Dependency {
    .product(name: name, package: "SailKit")
}

let package = Package(
    name: "SailApp",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "SailTerminalView", targets: ["SailTerminalView"]),
        .executable(name: "SailTerminalPreview", targets: ["SailTerminalPreview"]),
        .executable(name: "SailDrive", targets: ["SailDrive"]),
    ],
    dependencies: [.package(path: "../SailKit")],
    targets: [
        .target(name: "SailLook", dependencies: [kit("SailSurface"), kit("SailVT")], swiftSettings: strict),
        .target(name: "SailRenderer", dependencies: ["SailLook", kit("SailSurface"), kit("SailVT")], swiftSettings: strict),
        .target(
            name: "SailTerminalView",
            dependencies: ["SailRenderer", "SailLook", kit("SailSurface"), kit("SailTerminal"), kit("SailVT")],
            swiftSettings: strict
        ),
        .target(
            name: "SailPreview",
            dependencies: ["SailTerminalView", "SailLook", kit("SailSurface"), kit("SailTerminal"), kit("SailTerminalWire"), kit("SailVT")],
            swiftSettings: strict
        ),
        .executableTarget(name: "SailTerminalPreview", dependencies: ["SailPreview"], swiftSettings: strict),
        .executableTarget(
            name: "SailDrive",
            dependencies: ["SailPreview", "SailTerminalView", "SailRenderer", kit("SailSurface"), kit("SailTerminal"), kit("SailVT")],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailRendererTests",
            dependencies: ["SailRenderer", "SailLook", kit("SailSurface"), kit("SailVT"), kit("SailKitTestSupport")],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailTerminalViewTests",
            dependencies: ["SailTerminalView", "SailRenderer", "SailLook", kit("SailSurface"), kit("SailTerminal"), kit("SailVT")],
            swiftSettings: strict
        ),
    ],
    swiftLanguageModes: [.v6]
)
