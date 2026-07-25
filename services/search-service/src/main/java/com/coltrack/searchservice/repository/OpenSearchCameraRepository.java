package com.coltrack.searchservice.repository;


import com.coltrack.searchservice.document.CameraDocument;
import lombok.RequiredArgsConstructor;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.springframework.stereotype.Repository;


@Repository
@RequiredArgsConstructor
public class OpenSearchCameraRepository {


    private final OpenSearchClient client;


    public void save(CameraDocument document) {


        try {


            boolean exists =
                    client.indices()
                            .exists(e ->
                                    e.index("cameras")
                            )
                            .value();


            if (!exists) {

                client.indices()
                        .create(c ->
                                c.index("cameras")
                        );
            }


            client.index(
                    i -> i
                            .index("cameras")
                            .id(document.getCameraId().toString())
                            .document(document)
            );


        } catch (Exception e) {

            throw new RuntimeException(
                    "OpenSearch indexing failed",
                    e
            );
        }

    }
}
