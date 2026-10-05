package com.trinity.observability.config;

import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS policy for the browser UI.
 *
 * <p><b>PATCH must stay in the allowed methods.</b> The alerts API uses it for the
 * three partial updates that are not full replacements — {@code /enable},
 * {@code /trigger} and {@code /move} — and Spring rejects a disallowed method with
 * a bare {@code 403 Invalid CORS request} and no JSON body.</p>
 *
 * <p>That failure is easy to misdiagnose, because it does NOT require a
 * cross-origin deployment to happen: browsers send an {@code Origin} header on
 * same-origin requests too whenever the method is not GET or HEAD. Spring
 * therefore treats a same-origin PATCH from the dev server as a CORS request and
 * applies this list to it. GET is unaffected (no {@code Origin} header), which is
 * why the symptom was "the alert list loads and folders can be created, but
 * disabling an alert 403s". Reproducing it with curl also needs an explicit
 * {@code -H "Origin: http://localhost:5173"} — without that header the very same
 * request succeeds, which makes the backend look innocent.</p>
 *
 * <h2>Why the origins are PATTERNS, and configurable</h2>
 *
 * <p>The same trap bit the Dashboards screen, in its most confusing form yet: the
 * dashboard itself loaded (GETs, no {@code Origin} header) while EVERY PANEL
 * reported "Request failed (HTTP 403)", because a panel query is a POST and a POST
 * carries {@code Origin} even same-origin. The screen looked like a Grafana or
 * token problem and was neither — the dev server was simply on a port that a
 * hardcoded list of four origins did not name.</p>
 *
 * <p>Vite makes that trivially easy to hit: it silently falls back to 5174, 5175
 * and upwards when 5173 is taken, and prints the real port in a banner nobody
 * re-reads. So the default here is a wildcard PORT on both spellings of loopback
 * rather than four fixed origins — the trust boundary that actually matters is
 * "is this the developer's own machine", and the port a dev server happened to
 * pick is not a security property.</p>
 *
 * <p>It is a property, so a deployment that fronts this service on a real origin
 * pins that origin instead of inheriting a developer default:</p>
 *
 * <pre>CORS_ALLOWED_ORIGINS=https://observability.example.com</pre>
 *
 * <p>Patterns (not plain origins) are also what allows
 * {@code allowCredentials(true)} to coexist with a wildcard at all — Spring
 * rejects {@code allowedOrigins("*")} together with credentials, by design.</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LogManager.getLogger(CorsConfig.class);

    private final String[] originPatterns;

    public CorsConfig(@Value("${cors.allowed-origin-patterns}") String patterns) {
        this.originPatterns = Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (log.isInfoEnabled()) {
            log.info("CORS allowed origin patterns: {}", String.join(", ", originPatterns));
        }
        registry.addMapping("/**")
                // Both spellings of loopback are covered by the default. They are
                // the same interface and the same trust level, but DIFFERENT
                // origins to a browser — so allowing only one produces a 403 that
                // depends on which one the developer happened to type in the
                // address bar, with the symptom landing on whichever request first
                // needs a preflight.
                //
                // Vite in particular makes this easy to hit: started without an
                // explicit --host it binds IPv6-only on this machine, so
                // `http://localhost:5173` can be unreachable in Chrome and the
                // obvious workaround is to use the IP — which then 403s here.
                .allowedOriginPatterns(originPatterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
