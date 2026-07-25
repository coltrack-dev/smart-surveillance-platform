package com.coltrack.searchservice.config;


import org.apache.http.HttpHost;

import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class OpenSearchConfig {


    @Bean
    public OpenSearchClient openSearchClient() {


        RestClient restClient =
                RestClient.builder(
                                new HttpHost(
                                        "localhost",
                                        9200,
                                        "http"
                                )
                        )
                        .build();


        RestClientTransport transport =
                new RestClientTransport(
                        restClient,
                        new JacksonJsonpMapper()
                );


        return new OpenSearchClient(transport);
    }

}
