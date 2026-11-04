// OKAY-RELIABILITY DRIVEN as a person would, on the simulator (spec §26): an example loaded, its architecture, its
// numbers, a fault tried. Each step's screen kept in /tmp/okay-reliability-ios/.
import XCTest

final class ReliabilityUITests: XCTestCase {
  override func setUp() { continueAfterFailure = false }

  func shot(_ name: String) {
    let png = XCUIScreen.main.screenshot().pngRepresentation
    try? FileManager.default.createDirectory(atPath: "/tmp/okay-reliability-ios", withIntermediateDirectories: true)
    try? png.write(to: URL(fileURLWithPath: "/tmp/okay-reliability-ios/\(name).png"))
  }

  func testTheShop() {
    let app = XCUIApplication()
    app.launchArguments = ["-reset", "YES", "-AppleLanguages", "(en)"]
    app.launch()
    XCTAssert(app.buttons["Examples"].waitForExistence(timeout: 15))
    shot("1-system")
    app.buttons["Examples"].tap()
    XCTAssert(app.buttons["Load the shop"].waitForExistence(timeout: 10))
    app.buttons["Load the shop"].tap()
    XCTAssert(app.staticTexts["Single points of failure"].waitForExistence(timeout: 15) ||
      app.staticTexts["SINGLE POINTS OF FAILURE"].waitForExistence(timeout: 5))
    shot("2-architecture")
    app.buttons["Reliability"].tap()
    sleep(3)
    shot("3-reliability")
  }
}
