package tech.bhrigu.almira.shared.zk

/**
 * Hex without `String.format`, which is a JVM method — these tests run on
 * Kotlin/Native too, which is the entire point of them living in commonTest.
 */
internal fun hexOf(bytes: ByteArray): String =
    bytes.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
    }
