package com.sparkinvesco.jobflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class JobflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobflowApplication.class, args);
    }

    /**
     * Allow the React dev server (Vite default port 5173, with 5174/5175 fallbacks when
     * 5173 is held) to call the API. Localhost-only on both ends; do not widen this in
     * production without auth. We use allowedOriginPatterns so the Vite dev server can
     * pick the next free port (5174, 5175, …) automatically without a CORS regression.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(
                                "http://localhost:[0-9]+",
                                "http://127.0.0.1:[0-9]+"
                        )
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }
}
