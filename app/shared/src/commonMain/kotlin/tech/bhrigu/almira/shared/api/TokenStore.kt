package tech.bhrigu.almira.shared.api

/**
 * Where the session lives.
 *
 * An interface rather than a concrete class because the only correct
 * implementations are platform ones — the Android Keystore and the iOS Keychain
 * — and those arrive in their own stage. Everything above this line is written
 * against the interface now, so that stage is a swap rather than a rewrite.
 *
 * [InMemoryTokenStore] is the development stand-in and is deliberately named so
 * that shipping it by accident would be obvious in a stack trace.
 */
interface TokenStore {
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?
    suspend fun save(access: String, refresh: String)
    suspend fun clear()
}

/**
 * Survives nothing: a process restart signs you out.
 *
 * That is the right behaviour for a stand-in. A file-backed stop-gap would be
 * worse than useless — it would work well enough that nobody would feel the
 * need to replace it, and a refresh token in plain storage is exactly what the
 * Keychain and Keystore exist to prevent.
 */
class InMemoryTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null

    override suspend fun accessToken(): String? = access
    override suspend fun refreshToken(): String? = refresh

    override suspend fun save(access: String, refresh: String) {
        this.access = access
        this.refresh = refresh
    }

    override suspend fun clear() {
        access = null
        refresh = null
    }
}
