package tech.bhrigu.almira.shared.signin

import androidx.compose.ui.text.input.KeyboardType

/**
 * A number pad and nothing else claimed.
 *
 * Not `NumberPassword`: Compose Multiplatform 1.8.2's `getUITextInputTraits`
 * maps that to `UITextContentTypePassword`, which would have iOS offer saved
 * passwords over a one-time-code field.
 *
 * What we actually want here is `UITextContentTypeOneTimeCode`, and it cannot
 * be reached from Compose at this version: the whole iOS Compose UI klib
 * contains exactly three content-type constants — Password, EmailAddress and
 * TelephoneNumber — and the iOS text-input service never reads the
 * `ContentType.SmsOtpCode` semantics that `androidx.compose.ui.autofill`
 * declares. `getUITextInputTraits` takes `ImeOptions` and nothing else, and
 * `ImeOptions` has no content type in it.
 *
 * So this is the best available answer rather than the right one, and the gap
 * stays logged rather than quietly closed.
 */
internal actual val otpKeyboardType: KeyboardType = KeyboardType.Number
