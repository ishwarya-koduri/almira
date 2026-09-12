package tech.bhrigu.almira.shared.zk

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The same vectors, on a real device.
 *
 * The JVM tests next door prove the arithmetic against the JDK's provider.
 * Android's is Conscrypt — a different implementation of the same standard,
 * reached by the same `Mac.getInstance("HmacSHA256")` call — and "a different
 * implementation of a standard" is exactly the shape of assumption that has
 * cost this project time twice already. So it is run rather than reasoned about.
 *
 * `java.text.Normalizer` is likewise the platform's, and ICU on Android is not
 * the JDK's ICU.
 */
@RunWith(AndroidJUnit4::class)
class PassphraseKeyDeviceTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun derive(passphrase: String, salt: String, iterations: Int) =
        hex(PassphraseKey.derive(passphrase, salt.encodeToByteArray(), iterations))

    @Test
    fun publishedVectorHoldsOnDevice() {
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            derive("password", "salt", 4096),
        )
    }

    @Test
    fun webVectorHoldsOnDevice() {
        val fromTheBrowser = "935d4178ee85ced91649775a7bd92ff5860c282c6454fac281867daea61333d3"
        assertEquals(fromTheBrowser, derive("café pass ", "almira-interop-salt", 1000))
        assertEquals(fromTheBrowser, derive("café pass ", "almira-interop-salt", 1000))
    }

    @Test
    fun teluguVectorHoldsOnDevice() {
        assertEquals(
            "3d93d05a119bb63c9ea2697a91a70ff62cdd2993489a37e8fd354f0a7e042c03",
            derive("ఖజానా తాళం", "almira-interop-salt", 1000),
        )
    }

    /** And the full 600 000 rounds a real key uses, to see what it costs on a phone. */
    @Test
    fun realIterationCountIsUsable() {
        val started = System.currentTimeMillis()
        PassphraseKey.derive(
            "café pass ",
            "almira-interop-salt".encodeToByteArray(),
            PassphraseKey.NEW_KEY_ITERATIONS,
        )
        val took = System.currentTimeMillis() - started
        println("600000 iterations took ${took}ms on this device")
    }
}
