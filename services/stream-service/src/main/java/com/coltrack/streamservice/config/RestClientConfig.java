package com.coltrack.streamservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

/*
    @Bean
    public RestClient restClient() {

        return RestClient.builder()
                .baseUrl("http://localhost:8091")
                .build();

    }
*/

    @Bean
    RestClient restClient(
            @Value("${camera-service.url}")
            String url
    ) {

        return RestClient.builder()
                .baseUrl(url)
                .build();

    }
}
