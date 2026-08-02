package com.coltrack.gateway.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfiguration;


@Configuration
public class CorsConfig {


    @Bean
    public CorsWebFilter corsWebFilter() {


        CorsConfiguration config =
                new CorsConfiguration();


        config.addAllowedOrigin(
                "http://localhost:5173"
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
