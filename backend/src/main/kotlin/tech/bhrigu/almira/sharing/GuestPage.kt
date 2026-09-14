package tech.bhrigu.almira.sharing

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The page a guest link opens (known issue 43).
 *
 * `/share/<token>` and a helper's `/help/<token>` (continuity/HeirMode.kt) are
 * the same static page, `static/guest.html`. It holds nothing: it reads the
 * token from its own address, asks `/api/v1/share/<token>` (or `…/tasks`) for
 * the slice, and draws it read-only. The API is where the token is checked and
 * where the database narrows every read, so serving the page to anyone is safe
 * — and a forward rather than a controller keeps it out of the v1 contract,
 * which describes the API and nothing else.
 */
@Configuration
class GuestPageConfig : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/share/{token}").setViewName("forward:/guest.html")
        registry.addViewController("/help/{token}").setViewName("forward:/guest.html")
    }
}
