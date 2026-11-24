// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MBMobile",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MBInterface", targets: ["MBInterface"]),
    ],
    dependencies: [.package(url: "https://github.com/weichsel/ZIPFoundation.git", exact: "0.9.20")],
    targets: [
        .target(name: "MBSignal", linkerSettings: [.linkedFramework("MediaToolbox"), .linkedFramework("AudioToolbox")]),
        .target(name: "MBJavaScript", exclude: ["UPSTREAM.md", "vendor/LICENSE"], cSettings: [.define("CONFIG_VERSION", to: "\"2026-06-04\"")]),
        .target(name: "MBFoundation", dependencies: ["MBSignal", "MBJavaScript", .product(name: "ZIPFoundation", package: "ZIPFoundation")], resources: [.copy("Resources/PluginBootstrap.js")], linkerSettings: [.linkedLibrary("xml2")]),
        .target(name: "MBInterface", dependencies: ["MBFoundation"]),
        .testTarget(name: "MBFoundationTests", dependencies: ["MBFoundation", "MBSignal"], resources: [.copy("Fixtures")]),
    ],
    swiftLanguageModes: [.v6]
)

