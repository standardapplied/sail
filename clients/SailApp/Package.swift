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
        .executable(name: "Sail", targets: ["Sail"]),
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
        .target(name: "SailAccount", dependencies: [kit("SailAPI")], swiftSettings: strict),
        .target(
            name: "SailShell",
            dependencies: [
                "SailAccount", "SailLook", "SailTerminalView", kit("SailAPI"), kit("SailSurface"), kit("SailTerminal"), kit("SailTerminalWire"),
                kit("SailVT"), kit("SailWorkbench"),
            ],
            swiftSettings: strict
        ),
        .executableTarget(name: "Sail", dependencies: ["SailShell"], swiftSettings: strict),
        .target(name: "SailTestPasskey", dependencies: [kit("SailAPI")], swiftSettings: strict),
        .executableTarget(
            name: "SailDrive",
            dependencies: [
                "SailShell", "SailAccount", "SailTestPasskey", "SailTerminalView", "SailRenderer", kit("SailAPI"), kit("SailMeasures"), kit("SailSurface"),
                kit("SailTerminal"), kit("SailVT"), kit("SailWorkbench"),
            ],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailRendererTests",
            dependencies: ["SailRenderer", "SailLook", kit("SailSurface"), kit("SailVT"), kit("SailKitTestSupport")],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailTerminalViewTests",
            dependencies: ["SailTerminalView", "SailShell", "SailRenderer", "SailLook", kit("SailSurface"), kit("SailTerminal"), kit("SailTerminalWire"), kit("SailVT")],
            swiftSettings: strict
        ),
        .testTarget(
            name: "SailShellTests",
            dependencies: ["SailShell", "SailAccount", "SailLook", "SailTerminalView", "SailTestPasskey", kit("SailAPI"), kit("SailVT"), kit("SailWorkbench")],
            swiftSettings: strict
        ),
    ],
    swiftLanguageModes: [.v6]
)
