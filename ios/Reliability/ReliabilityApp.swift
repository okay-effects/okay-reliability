// okay-reliability's whole Swift: okay-faces' shell opening the phone's program by its class name (spec §26).
// `-reset YES` (a test): what the app kept forgotten first.
import SwiftUI
import OkayFaces

@main
struct ReliabilityApp: App {
  init() {
    let d = UserDefaults.standard
    if d.bool(forKey: "reset") {
      for k in d.dictionaryRepresentation().keys where k.hasPrefix("okay-") { d.removeObject(forKey: k) }
    }
  }
  var body: some Scene {
    WindowGroup { OkayFacesView(app: "okay.reliability.phone.ReliabilityApp") }
  }
}
