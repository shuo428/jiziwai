package springbootjni.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173") // Vite dev server
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Packaged desktop/runtime mode serves the React build from an external frontend folder.
        // Keeping this explicit avoids environment-specific 404s when the jar is launched with
        // an external application.properties. Classpath static resources remain as a fallback.
        registry.addResourceHandler("/index.html", "/vite.svg", "/favicon.ico")
                .addResourceLocations("file:./frontend/", "classpath:/static/");
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:./frontend/assets/", "classpath:/static/assets/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Forward frontend routes to index.html for React BrowserRouter.
        // The route segment may contain hyphens, for example /config-management.
        registry.addViewController("/")
                .setViewName("forward:/index.html");
        registry.addViewController("/{spring:[\\w\\-]+}")
                .setViewName("forward:/index.html");
        registry.addViewController("/**/{spring:[\\w\\-]+}")
                .setViewName("forward:/index.html");
    }
}
