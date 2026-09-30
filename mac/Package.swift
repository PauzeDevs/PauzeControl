// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PauzeControl",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "PauzeControl", targets: ["PauzeControl"])
    ],
    targets: [
        .executableTarget(
            name: "PauzeControl",
            path: "Sources/PauzeControl"
        )
    ]
)
