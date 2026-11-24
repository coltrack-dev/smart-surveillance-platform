package com.coltrack.streamservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

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

    @Bean("broadcastPlaybackRestClientBuilder")
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder broadcastPlaybackRestClientBuilder() {
        // Service-to-service HTTP must not turn upstream redirects into SSRF or credential forwarding.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }
}
