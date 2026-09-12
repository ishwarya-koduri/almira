package tech.bhrigu.almira.shared.signin

import androidx.compose.ui.text.input.KeyboardType

/**
 * The keyboard the six-cell code field asks for, which is not the same answer
 * on both platforms.
 *
 * `NumberPassword` is right on Android: a digits-only keypad that also stops
 * the keyboard offering its own suggestions, learning the code, or putting it
 * on the clipboard strip.
 *
 * On iOS the same value is actively wrong, and that took reading Compose's own
 * iOS text-input mapping to find. `getUITextInputTraits` turns `Password` and
 * `NumberPassword` into `UITextContentTypePassword` — so the field would tell
 * iOS it is a password, and iOS would offer saved passwords and Strong Password
 * on a six-digit one-time code. That is worse than offering nothing, and it is
 * the opposite of what the field wants to say.
 *
 * A plain `Number` claims no content type at all, which is the most iOS can be
 * told here — see [otpKeyboardType]'s iOS actual and docs/known-issues.md for
 * why `.oneTimeCode` itself is out of reach.
 */
internal expect val otpKeyboardType: KeyboardType
