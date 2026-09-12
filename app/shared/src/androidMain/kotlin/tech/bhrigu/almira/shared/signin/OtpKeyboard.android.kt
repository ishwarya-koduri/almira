package tech.bhrigu.almira.shared.signin

import androidx.compose.ui.text.input.KeyboardType

/**
 * Digits only, and kept out of the keyboard's suggestions and its dictionary.
 * The cells draw the digits themselves, so nothing is hidden from the person
 * typing — only from the keyboard.
 */
internal actual val otpKeyboardType: KeyboardType = KeyboardType.NumberPassword
