package springbootjni.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Serves the packaged React frontend in desktop/runtime mode.
 *
 * <p>The application is primarily a Spring Boot API server, but the packaged
 * distribution also carries a compiled React frontend under {@code ./frontend}.
 * Relying only on Spring Boot's default static-resource discovery can be brittle
 * when the jar is launched with an external working directory and an external
 * application.properties. This controller makes the runtime contract explicit:
 * {@code /index.html} and {@code /assets/**} are read from the package frontend
 * folder first, then from the jar's classpath static folder as a fallback.</p>
 */
@Controller
public class FrontendStaticController {

    @Value("${spectral.frontend.root:./frontend}")
    private String frontendRoot;

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> index() {
        return serveFrontendFile("index.html", false);
    }

    @GetMapping("/assets/**")
    public ResponseEntity<Resource> asset(HttpServletRequest request) {
        String mappedPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (mappedPath == null || !mappedPath.startsWith("/assets/")) {
            return ResponseEntity.notFound().build();
        }
        String relativePath = mappedPath.substring(1);
        return serveFrontendFile(relativePath, true);
    }

    private ResponseEntity<Resource> serveFrontendFile(String relativePath, boolean cacheable) {
        if (relativePath == null || relativePath.trim().isEmpty()
                || relativePath.contains("..")
                || relativePath.startsWith("/")
                || relativePath.startsWith("\\")) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = resolveExternalResource(relativePath);
        if (resource == null) {
            resource = resolveClasspathResource(relativePath);
        }
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(resolveMediaType(relativePath));
        if (cacheable) {
            builder.cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
        } else {
            builder.cacheControl(CacheControl.noCache());
        }
        return builder.body(resource);
    }

    private Resource resolveExternalResource(String relativePath) {
        try {
            Path root = Paths.get(frontendRoot).toAbsolutePath().normalize();
            Path target = root.resolve(relativePath.replace('/', java.io.File.separatorChar)).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                return null;
            }
            return new FileSystemResource(target.toFile());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Resource resolveClasspathResource(String relativePath) {
        ClassPathResource resource = new ClassPathResource("static/" + relativePath);
        return resource.exists() ? resource : null;
    }

    private MediaType resolveMediaType(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return MediaType.TEXT_HTML;
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return MediaType.valueOf("application/javascript");
        }
        if (lower.endsWith(".css")) {
            return MediaType.valueOf("text/css");
        }
        if (lower.endsWith(".svg")) {
            return MediaType.valueOf("image/svg+xml");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        try {
            String probed = Files.probeContentType(Paths.get(relativePath));
            return probed == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(probed);
        } catch (IOException | RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
