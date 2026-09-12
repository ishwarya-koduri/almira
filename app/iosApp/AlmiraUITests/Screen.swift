import XCTest

/// The little that a UI test of a Compose app can lean on.
///
/// Compose draws into one Skia surface, so there are no UIKit views to query by
/// identifier the way a SwiftUI test would. What there *is* is the accessibility
/// tree Compose publishes — XCUITest is an accessibility client, so asking for
/// it is what turns it on — plus coordinate taps, which always work because
/// they go in as touches below the view layer entirely.
///
/// So: read with the accessibility tree, act by tapping the element the tree
/// found, and fall back to coordinates only where nothing is exposed.
enum Screen {

    // MARK: - Reading

    /// A query for "any element whose label contains this".
    ///
    /// Compose exposes labels but no identifiers, so a substring match on the
    /// label is the only handle there is.
    private static func matching(_ app: XCUIApplication, _ needle: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", needle))
            .firstMatch
    }

    /// Waits for a string to appear.
    ///
    /// Uses `waitForExistence` rather than polling a rebuilt text list. That is
    /// not a stylistic preference: asking for the whole hierarchy while Compose
    /// is mid-navigation fails the test outright with "Failed to get matching
    /// snapshot", because a snapshot error is not something Swift can catch.
    /// `waitForExistence` polls inside XCUITest and rides those out.
    @discardableResult
    static func waitForText(
        _ app: XCUIApplication,
        _ needle: String,
        timeout: TimeInterval = 25
    ) -> Bool {
        matching(app, needle).waitForExistence(timeout: timeout)
    }

    /// Is this on screen now? A second of grace, because "now" during an
    /// animation is not a well-defined moment.
    static func showing(_ app: XCUIApplication, _ needle: String) -> Bool {
        matching(app, needle).waitForExistence(timeout: 1.5)
    }

    /// Is this reliably *not* on screen?
    ///
    /// Separate from `!showing` and given a longer window on purpose: a
    /// negative assertion that something private is absent must not pass
    /// merely because it had not rendered yet. This is the shape every
    /// privacy check in these tests uses.
    static func absent(_ app: XCUIApplication, _ needle: String) -> Bool {
        !matching(app, needle).waitForExistence(timeout: 5)
    }

    /// Every string the app exposes, for the evidence dump. Only called when
    /// the screen has already settled — see the note on `waitForText`.
    static func texts(_ app: XCUIApplication) -> [String] {
        app.descendants(matching: .any)
            .allElementsBoundByAccessibilityElement
            .compactMap { $0.label.isEmpty ? nil : $0.label }
    }

    // MARK: - Acting

    /// Taps the first element whose label contains `needle`.
    ///
    /// Uses the element's own frame rather than a hit-test by identifier,
    /// because Compose exposes labels but not identifiers, and two nodes can
    /// carry the same label — the button and the text inside it, for instance.
    /// Clears iOS's first-run keyboard tutorials.
    ///
    /// They arrive as sheets over the entire app the first time a keyboard with
    /// more than one language appears, and they swallow every tap underneath
    /// without appearing in the app's own tree at all. `run-ui.sh` marks them
    /// as already seen, but a freshly erased device will still show one, so
    /// this stays as a belt to that brace.
    static func dismissSystemIntros(_ app: XCUIApplication) {
        for label in ["Continue", "Allow", "OK"] {
            let button = app.buttons[label]
            if button.exists && button.isHittable {
                button.tap()
                usleep(600_000)
            }
        }
    }

    @discardableResult
    static func tapText(_ app: XCUIApplication, _ needle: String, timeout: TimeInterval = 25) -> Bool {
        guard matching(app, needle).waitForExistence(timeout: timeout) else { return false }

        // Prefer a button over the text inside it. Compose publishes both, and
        // `firstMatch` on a plain "any element" query can hand back the label
        // — whose tap the button above it never sees. That was a whole
        // afternoon of taps that registered as nothing.
        let predicate = NSPredicate(format: "label CONTAINS %@", needle)
        let candidates = [
            app.buttons.matching(predicate).firstMatch,
            matching(app, needle),
        ]

        for element in candidates {
            guard element.exists else { continue }

            // Not hittable means one of two things, needing opposite responses:
            // below the fold, or covered by the system's Face ID sheet.
            // Scrolling fixes the first and cannot fix the second, so scroll,
            // then give up rather than tapping blind — a tap that lands on the
            // sheet is worse than a clear failure.
            //
            // The loop stops when the frame stops *moving*, not when the
            // element first becomes hittable. Those differ by one fling, and
            // that difference cost an hour: a button becomes hittable while
            // still sliding, so the tap went to where it had been a moment
            // earlier, landed on a category row, and did nothing at all — a
            // tap that reports success and achieves nothing being the worst
            // of the available outcomes.
            if !element.isHittable {
                var previous = element.frame
                for _ in 0..<6 {
                    app.swipeUp()
                    usleep(900_000)
                    let current = element.frame
                    if current.equalTo(previous) && element.isHittable { break }
                    previous = current
                }
            }
            guard element.isHittable else { continue }
            usleep(600_000)
            element.tap()
            return true
        }
        return false
    }

    /// Taps a point given as a fraction of the window, so the same test reads
    /// correctly on any simulator size.
    static func tap(_ app: XCUIApplication, x: Double, y: Double) {
        app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
    }

    // MARK: - The one-time code

    /// The six digits, read off the app's own screen.
    ///
    /// The backend tells the client when no SMS provider is configured, and the
    /// code step then says so in as many words — "No SMS provider is
    /// configured, so the code is 838100." That is a deliberate development
    /// affordance in the product, not a test hook, and reading it is both
    /// simpler and more honest than the first two attempts:
    ///
    ///   - a host script writing the code into the simulator's device-wide
    ///     `/tmp`, which a UI test cannot see because it is itself a sandboxed
    ///     app with a `/tmp` of its own; then
    ///   - the same script serving it over loopback, which worked but made the
    ///     test depend on a second process tailing Docker logs.
    ///
    /// The code still travels the whole way round — app asks the real API, the
    /// real sender produces it — and nothing outside the app is involved.
    static func readCodeOffScreen(_ app: XCUIApplication, timeout: TimeInterval = 30) -> String? {
        let marker = "so the code is"
        guard waitForText(app, marker, timeout: timeout) else { return nil }
        let line = texts(app).first { $0.contains(marker) } ?? ""
        let digits = line.components(separatedBy: CharacterSet.decimalDigits.inverted)
            .first { $0.count == 6 }
        return digits
    }

    /// Asks the Mac to post the Face-ID-matched notification with `simctl`.
    ///
    /// A fallback for the in-simulator post, and kept deliberately: the
    /// Simulator app does it from the host, so if the in-process route is ever
    /// ignored this is the route that is known to work.
    static func matchFaceIdFromHost() {
        var request = URLRequest(url: URL(string: "http://localhost:18099/faceid/match")!)
        request.timeoutInterval = 8
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, _, _ in done.signal() }.resume()
        _ = done.wait(timeout: .now() + 9)
    }

    /// Asks the host to soft-delete anything the capture test left behind,
    /// through the product's own DELETE endpoint as a real user.
    ///
    /// A test that changes a household and walks away leaves the next run
    /// asserting against a moved baseline — and the acceptance figures in
    /// docs/ are absolute, so the litter would quietly invalidate them.
    static func cleanUpTestHoldings() -> String {
        var request = URLRequest(url: URL(string: "http://localhost:18099/cleanup")!)
        request.timeoutInterval = 90
        var result = "cleanup: no answer from the bridge"
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, _, _ in
            if let data, let text = String(data: data, encoding: .utf8) { result = text }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 95)
        return result
    }

    // MARK: - Evidence

    /// Attaches the hierarchy, every exposed string and a screenshot, so a
    /// failure is diagnosable from the result bundle rather than by re-running.
    static func record(_ app: XCUIApplication, _ label: String, to test: XCTestCase) {
        let strings = XCTAttachment(string: texts(app).joined(separator: "\n"))
        strings.name = "texts-\(label)"
        strings.lifetime = .keepAlways
        test.add(strings)

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "screen-\(label)"
        shot.lifetime = .keepAlways
        test.add(shot)

        print("=== \(label) ===")
        print(texts(app).joined(separator: " | "))
    }
}
