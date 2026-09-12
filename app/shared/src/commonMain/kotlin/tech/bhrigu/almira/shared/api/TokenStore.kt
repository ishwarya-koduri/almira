package tech.bhrigu.almira.shared.api

/**
 * Where the session lives.
 *
 * An interface rather than a concrete class because the only correct
 * implementations are platform ones — the Android Keystore and the iOS
 * Keychain. Both are reached through
 * `tech.bhrigu.almira.shared.security.createTokenStore`, and nothing above this
 * line knows which one it is holding.
 *
 * There is deliberately no in-memory implementation any more. One existed while
 * the platform stores were unwritten and is gone now that they are not: a
 * stand-in that works well enough is how a stand-in survives to production.
 */
interface TokenStore {
    /**
     * Whether a session is stored at all.
     *
     * Answered **without decrypting**, which is the whole point: on Android the
     * key needs a recent unlock, so asking for the token itself cannot
     * distinguish "signed out" from "locked". This can, and it is what decides
     * whether a returning person is shown a lock or a sign-in.
     */
    suspend fun hasSession(): Boolean

    /** Null when absent, and also null when present but still locked. */
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?

    suspend fun save(access: String, refresh: String)

    /** Sign out: the stored session is destroyed, not merely hidden. */
    suspend fun clear()

    /**
     * Forget anything held in memory, keeping what is stored.
     *
     * Called when the app leaves the foreground. Without it, "locked" would be
     * a screen drawn over a session that is still perfectly readable — the
     * difference between a curtain and a lock.
     */
    suspend fun forget()
}
