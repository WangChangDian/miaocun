// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "VideoGrabber",
    platforms: [
        .macOS(.v13)
    ],
    targets: [
        .executableTarget(
            name: "VideoGrabber",
            path: "Sources/VideoGrabber"
        )
    ]
)
