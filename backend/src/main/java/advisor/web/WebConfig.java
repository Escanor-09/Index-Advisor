package advisor.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The frontend (Vite dev server, a different origin/port) talks to this API
 * from the browser, not from a server-to-server call — without this, every
 * request is blocked by the browser's own CORS enforcement before it ever
 * reaches AdvisorController, regardless of how correct the API itself is.
 * Origin is externalized (cors.allowed-origin / CORS_ALLOWED_ORIGIN) rather
 * than hardcoded, same pattern as the DB config, so a production frontend
 * origin can be set without a code change.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    public WebConfig(@Value("${cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(allowedOrigin)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*");
    }
}
