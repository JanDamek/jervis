# jervis/ios

Purpose: iOS client written in Swift (SwiftUI or UIKit), consuming shared Kotlin logic via KMM if needed.

Tooling:
- Build: Xcode (xcodebuild) and/or Swift Package Manager
- Language: Swift; optional Kotlin Multiplatform integration from `jervis/core`

Notes:
- Networking and platform APIs implemented natively in Swift. Shared business logic can be bridged from Kotlin when beneficial.
