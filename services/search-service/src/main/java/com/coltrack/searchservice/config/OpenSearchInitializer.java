package com.coltrack.searchservice.config;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.opensearch.client.opensearch.OpenSearchClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchInitializer {


    private static final String CAMERA_INDEX = "cameras";


    private final OpenSearchClient client;


    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {


        try {


            boolean exists =
                    client.indices()
                            .exists(e ->
                                    e.index(CAMERA_INDEX)
                            )
                            .value();


            if (exists) {


                log.info(
                        "OpenSearch index '{}' already exists",
                        CAMERA_INDEX
                );


                return;
            }


            client.indices()
                    .create(c ->
                            c.index(CAMERA_INDEX)
                                    .mappings(m ->
                                            m.properties(
                                                            "cameraId",
                                                            p -> p
                                                                    .keyword(k -> k)
                                                    )
                                                    .properties(
                                                            "name",
                                                            p -> p
                                                                    .text(t -> t)
                                                    )
                                                    .properties(
                                                            "location",
                                                            p -> p
                                                                    .text(t -> t)
                                                    )
                                                    .properties(
                                                            "createdAt",
                                                            p -> p
                                                                    .date(d -> d)
                                                    )
                                    )
                    );


            log.info(
                    "OpenSearch index '{}' created successfully",
                    CAMERA_INDEX
            );


        } catch (Exception e) {


            log.error(
                    "Failed to initialize OpenSearch index '{}'",
                    CAMERA_INDEX,
                    e
            );


            throw new IllegalStateException(
                    "OpenSearch initialization failed",
                    e
            );

        }

    }

}
