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

    // MARK: - The OTP bridge

    /// The six digits the backend just sent, as bridged in from the host.
    ///
    /// The app asks the real API for the code — that is the path under test —
    /// and the backend's dev sender logs it. A script on the Mac tails that log
    /// and writes the digits into a directory served on the Mac's loopback,
    /// which the simulator reaches as `localhost`. Nothing in the app or the
    /// API is changed or stubbed to make this work: the code still travels the
    /// whole way round, app → API → sender → app.
    ///
    /// Not the simulator's own `/tmp`, which was the first attempt: a UI test
    /// process is itself a sandboxed app, so its `/tmp` is its own container
    /// rather than the device-wide directory, and the file written by the host
    /// was never visible to it.
    static let otpURL = URL(string: "http://localhost:18099/almira-otp.txt")!

    private static func fetchBridgedCode() -> String? {
        var result: String?
        let done = DispatchSemaphore(value: 0)
        var request = URLRequest(url: otpURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        URLSession.shared.dataTask(with: request) { data, response, _ in
            defer { done.signal() }
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let data, let text = String(data: data, encoding: .utf8) else { return }
            result = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }.resume()
        _ = done.wait(timeout: .now() + 5)
        return result
    }

    /// Drops any code from an earlier step, so a stale one cannot be mistaken
    /// for the one this sign-in asked for.
    static func clearBridgedCode() {
        var request = URLRequest(url: URL(string: "http://localhost:18099/clear")!)
        request.httpMethod = "GET"
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, _, _ in done.signal() }.resume()
        _ = done.wait(timeout: .now() + 5)
    }

    static func awaitBridgedCode(timeout: TimeInterval = 40) -> String? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let code = fetchBridgedCode(), code.count == 6 { return code }
            usleep(500_000)
        }
        return nil
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
