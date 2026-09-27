package tech.almira.market

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Public market data: mutual-fund NAVs, exchange closing prices, and reference
 * exchange rates (docs/13 §6).
 *
 * **Off unless switched on.** Nothing here makes a network call in a fresh
 * checkout, in the test suites, or in a deployment that has not decided to: the
 * two jobs and the one class that can reach the internet exist only when their
 * `enabled` flag is true. Every figure these feeds produce is public — no
 * account, no key, nothing about a household is ever sent — but an app that
 * quietly phones exchanges from a family's server is not one anyone chose.
 */
@ConfigurationProperties("almira.market-data")
data class MarketDataProperties(
    val fx: Fx = Fx(),
    val prices: Prices = Prices(),
) {
    /** Reference exchange rates from the European Central Bank. */
    data class Fx(
        val enabled: Boolean = false,
        val url: String = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml",
        val timeout: Duration = Duration.ofSeconds(20),
    )

    /** AMFI's daily NAV file and the NSE and BSE end-of-day bhavcopies. */
    data class Prices(
        val enabled: Boolean = false,
        val amfiUrl: String = "https://portal.amfiindia.com/spages/NAVAll.txt",
        /** `{date}` is replaced with the trading day as yyyyMMdd. */
        val nseUrl: String = "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_{date}_F_0000.csv.zip",
        val bseUrl: String = "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_{date}_F_0000.CSV",
        /** Which of `amfi`, `nse`, `bse` to read. */
        val sources: List<String> = listOf("amfi", "nse", "bse"),
        val timeout: Duration = Duration.ofSeconds(60),
    )
}

/** Fetches one public file. An interface so the tests hand in fixtures and never touch the network. */
fun interface MarketFileFetcher {
    fun fetch(url: String, timeout: Duration): ByteArray
}

/**
 * The only thing in this package that reaches the internet, and it exists only
 * when a market-data feed is switched on.
 *
 * HTTPS only, a hard size cap (the full NAV file is about 1.5 MB, a bhavcopy
 * well under that), no redirects to somewhere else, and no cookies or
 * credentials of any kind: these are public files, fetched the way a browser
 * would fetch them, and nothing of the household's goes out with the request.
 */
@Component
@ConditionalOnExpression("\${almira.market-data.fx.enabled:false} or \${almira.market-data.prices.enabled:false}")
class HttpMarketFileFetcher : MarketFileFetcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun fetch(url: String, timeout: Duration): ByteArray {
        val uri = URI.create(url)
        require(uri.scheme == "https") { "market data is only fetched over https" }
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            // Exchanges refuse requests with no user agent; say plainly what this is.
            .header("User-Agent", "Mozilla/5.0 (compatible; Almira market data)")
            .header("Accept", "*/*")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw MarketFileUnavailable("HTTP ${response.statusCode()} from ${uri.host}")
        }
        response.body().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) throw MarketFileUnavailable("file from ${uri.host} is larger than expected")
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}

/** A feed that was not there today: a holiday, a late publication, a changed address. Not an error to page anyone for. */
class MarketFileUnavailable(message: String) : RuntimeException(message)
