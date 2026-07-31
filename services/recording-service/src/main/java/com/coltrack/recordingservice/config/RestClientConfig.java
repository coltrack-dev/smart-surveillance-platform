package com.coltrack.recordingservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;


@Configuration
public class RestClientConfig {


    @Bean
    public RestClient restClient(
            @Value("${camera-service.url}") String cameraServiceUrl
    ) {

        return RestClient.builder()
                .baseUrl(cameraServiceUrl)
                .build();
    }
}
