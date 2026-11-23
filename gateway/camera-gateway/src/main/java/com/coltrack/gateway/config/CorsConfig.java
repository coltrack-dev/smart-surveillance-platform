package com.coltrack.gateway.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.beans.factory.annotation.Value;


@Configuration
public class CorsConfig {

    @Value("${CORS_ALLOWED_ORIGIN:http://localhost:5173}")
    private String allowedOrigin;


    @Bean
    public CorsWebFilter corsWebFilter() {


        CorsConfiguration config =
                new CorsConfiguration();


        config.addAllowedOrigin(
                allowedOrigin
        );


        config.addAllowedHeader("*");

        config.addAllowedMethod("*");


        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();


        source.registerCorsConfiguration(
                "/**",
                config
        );


        return new CorsWebFilter(source);
    }
}
