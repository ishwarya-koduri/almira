package tech.bhrigu.almira.config

import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory
import org.springframework.boot.web.server.MimeMappings
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

/**
 * One MIME type the container does not know.
 *
 * Tomcat's built-in map has no entry for `.webmanifest`, so the web app
 * manifest went out as `application/octet-stream`. Browsers mostly tolerate
 * that; the specification says `application/manifest+json`, and an installable
 * app that depends on a browser being forgiving is not really installable.
 */
@Component
class StaticContentConfig : WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    override fun customize(factory: ConfigurableServletWebServerFactory) {
        val mappings = MimeMappings.lazyCopy(MimeMappings.DEFAULT)
        mappings.add("webmanifest", "application/manifest+json")
        factory.setMimeMappings(mappings)
    }
}
