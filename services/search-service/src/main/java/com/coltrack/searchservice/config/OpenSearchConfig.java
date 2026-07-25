package com.coltrack.searchservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
    public OpenSearchClient openSearchClient(
            ObjectMapper objectMapper
    ) {


        ObjectMapper mapper =
                objectMapper.copy();

        mapper.registerModule(
                new JavaTimeModule()
        );


        RestClient restClient =
                RestClient.builder(
                        new HttpHost(
                                "localhost",
                                9200,
                                "http"
                        )
                ).build();


        RestClientTransport transport =
                new RestClientTransport(
                        restClient,
                        new JacksonJsonpMapper(mapper)
                );


        return new OpenSearchClient(
                transport
        );
    }
}
