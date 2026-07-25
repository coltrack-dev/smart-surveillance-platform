package com.coltrack.searchservice.config;


import lombok.RequiredArgsConstructor;

import org.opensearch.client.opensearch.OpenSearchClient;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;



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



            if (!exists) {


                client.indices()
                        .create(c ->
                                c.index(CAMERA_INDEX)
                        );


                System.out.println(
                        "Created OpenSearch index: "
                                + CAMERA_INDEX
                );

            } else {


                System.out.println(
                        "OpenSearch index already exists: "
                                + CAMERA_INDEX
                );

            }


        } catch (Exception e) {


            throw new RuntimeException(
                    "OpenSearch initialization failed",
                    e
            );

        }

    }

}
