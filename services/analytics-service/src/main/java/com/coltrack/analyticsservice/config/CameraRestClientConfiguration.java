package com.coltrack.analyticsservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CameraRestClientConfiguration {
    @Bean
    RestClient cameraRestClient(
            @Value("${camera-service.url:http://localhost:8091}") String baseUrl
    ) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
