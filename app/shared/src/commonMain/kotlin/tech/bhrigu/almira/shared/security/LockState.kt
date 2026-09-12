package tech.bhrigu.almira.shared.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is currently behind its lock.
 *
 * Common state, driven by platform lifecycle: Android's activity calls [lock]
 * from `onStop`, iOS will call it from `applicationDidEnterBackground`. Neither
 * piece of knowledge belongs in shared code, and neither does the decision it
 * produces belong in platform code — so the event crosses here and nowhere
 * else.
 *
 * It starts locked. A cold start is exactly the case the lock exists for, and
 * defaulting the other way would mean the one launch that skips the prompt is
 * the one after a reboot.
 */
class LockState {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _inForeground = MutableStateFlow(false)

    /**
     * Whether the app's window is actually resumed.
     *
     * The prompt cannot be raised before it is. Asking earlier looks like it
     * worked — the call returns and the button goes busy — and then no dialog
     * appears and no callback ever arrives, which is a spinner that never
     * stops. Backgrounding re-locks, which would otherwise ask for the prompt
     * at exactly the moment it cannot be shown, so the answer is to wait for
     * the resume rather than for a composition.
     */
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    /**
     * Left the foreground. The next look at this app has to ask again, and
     * nothing may raise a dialog until it is back.
     */
    fun backgrounded() {
        _inForeground.value = false
        _locked.value = true
    }

    /** Back in front, and resumed: it is safe to show a dialog now. */
    fun foregrounded() {
        _inForeground.value = true
    }

    /** The prompt was passed. */
    fun unlocked() {
        _locked.value = false
    }
}
