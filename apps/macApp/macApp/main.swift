import AppKit

// Pure AppKit entry point. A SwiftUI App with only a `Settings` scene
// proved unreliable at driving `applicationDidFinishLaunching` under
// `LSUIElement=true` (the delegate was never invoked → no APNs
// registration → no token). This keeps the lifecycle explicit: create
// NSApplication, attach our delegate, run.

let delegate = AppDelegate()
let app = NSApplication.shared
app.delegate = delegate
app.setActivationPolicy(.accessory) // menubar-only, matches LSUIElement
app.run()
