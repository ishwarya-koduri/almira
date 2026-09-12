package tech.bhrigu.almira.shared.security

import kotlinx.coroutines.runBlocking

/**
 * Proof that the two iOS actuals next door work at runtime, not merely that
 * they compile.
 *
 * The Keychain store is hand-built `CFDictionaryCreateMutable` work against a C
 * API that reports failure by returning an `OSStatus` nobody is obliged to
 * read. That is precisely the shape of code that compiles, links, launches, and
 * silently stores nothing — so it gets exercised on the way up rather than
 * discovered during the thin slice.
 *
 * Skips itself the moment there is a real session stored, so it can never
 * disturb one. That matters: a later stage has to show a token surviving a
 * force-quit, and a self-test that overwrote the session on every launch would
 * quietly make that impossible to prove.
 */
fun securitySelfTest(): String {
    val lines = mutableListOf<String>()
    val store = createTokenStore(PlatformHost())
    val lock = createAppLock(PlatformHost())

    lines += "lock availability: ${lock.availability()}"

    runBlocking {
        if (store.hasSession()) {
            lines += "keychain: skipped — a real session is stored, leaving it alone"
            return@runBlocking
        }

        val access = "self-test-access"
        val refresh = "self-test-refresh"
        store.save(access, refresh)

        // `forget` drops the in-memory cache, so the reads below have to go
        // back through the Keychain. Without this the next two lines would
        // only prove the cache works.
        store.forget()

        val readBack = store.accessToken()
        val refreshBack = store.refreshToken()
        val present = store.hasSession()
        lines += if (readBack == access && refreshBack == refresh && present) {
            "keychain: wrote and read back both tokens through SecItemAdd/SecItemCopyMatching"
        } else {
            "keychain: FAILED — access=${describe(readBack, access)} " +
                "refresh=${describe(refreshBack, refresh)} hasSession=$present"
        }

        store.clear()
        store.forget()
        val afterClear = store.accessToken()
        val sessionAfterClear = store.hasSession()
        lines += if (afterClear == null && !sessionAfterClear) {
            "keychain: cleared — nothing left behind by this self-test"
        } else {
            "keychain: FAILED to clear — access=${afterClear != null} session=$sessionAfterClear"
        }
    }

    return lines.joinToString("\n")
}

/** Never prints the value, only whether it came back and whether it matched. */
private fun describe(actual: String?, expected: String): String = when {
    actual == null -> "absent"
    actual == expected -> "match"
    else -> "wrong length ${actual.length}"
}
