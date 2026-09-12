import CryptoKit
import Foundation
import Shared

/// The one primitive the shared module cannot reach on its own.
///
/// Everything else iOS-specific in this app is C and is called from Kotlin
/// directly — the Keychain, Face ID, PBKDF2 and HMAC over CommonCrypto, the
/// random from Security. AES-256-GCM is the exception: it lives in CryptoKit,
/// which is Swift-only, and the public CommonCrypto headers shipped in the iOS
/// SDK declare no GCM at all. So this is the whole of the reason any Swift
/// beyond the app shell exists.
///
/// Nothing in here decides anything. The key, the nonce, the additional data
/// and the byte layout are all settled above the seam, in `commonMain`, so this
/// file cannot introduce a divergence between iOS and the other two clients —
/// which is exactly what `zkSelfTest()` checks at launch.
final class CryptoKitAead: NSObject, AppleAead {

    func seal(key: Data, iv: Data, plaintext: Data, aad: Data) -> Data {
        do {
            let box = try AES.GCM.seal(
                plaintext,
                using: SymmetricKey(data: key),
                nonce: AES.GCM.Nonce(data: iv),
                authenticating: aad
            )
            // The envelope format keeps the tag attached to the ciphertext and
            // the nonce in its own field, so this returns those two halves and
            // not CryptoKit's `combined`, which would prepend the nonce again.
            return box.ciphertext + box.tag
        } catch {
            // Reachable only from a wrong key or nonce length, both of which
            // `aesGcmSeal` requires before calling. Returning empty or partial
            // data here would write an unopenable value into someone's records
            // and report success, so this stops instead.
            fatalError("AES-GCM seal failed with well-formed inputs: \(error)")
        }
    }

    func unseal(key: Data, iv: Data, body: Data, aad: Data) -> Data? {
        // A body shorter than the tag is not a failure to authenticate, it is
        // not an envelope — but it reaches the caller as the same nil, because
        // telling the two apart tells an attacker which half they got right.
        guard body.count > 16 else { return nil }
        let tagStart = body.index(body.endIndex, offsetBy: -16)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: iv),
                ciphertext: body[body.startIndex..<tagStart],
                tag: body[tagStart..<body.endIndex]
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            return nil
        }
    }
}
