// swift-tools-version: 5.9
// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

import PackageDescription

let package = Package(
    name: "PauzeControl",
    platforms: [.macOS(.v14)],
    products: [
        .executable(
            name: "PauzeControl",
            targets: ["PauzeControl"]
        )
    ],
    targets: [
        .target(
            name: "PauzeControlHTTP",
            path: "Sources/PauzeControlHTTP"
        ),
        .executableTarget(
            name: "PauzeControl",
            dependencies: ["PauzeControlHTTP"],
            path: "Sources/PauzeControl"
        ),
        .testTarget(
            name: "PauzeControlTests",
            dependencies: ["PauzeControlHTTP"],
            path: "Tests/PauzeControlTests"
        )
    ]
)
