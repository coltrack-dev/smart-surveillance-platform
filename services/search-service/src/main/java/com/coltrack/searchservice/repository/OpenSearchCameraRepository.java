package com.coltrack.searchservice.repository;


import com.coltrack.searchservice.document.CameraDocument;
import lombok.RequiredArgsConstructor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.stereotype.Repository;


@Repository
@RequiredArgsConstructor
public class OpenSearchCameraRepository {


    private static final String INDEX = "cameras";


    private final OpenSearchClient client;


    public void save(CameraDocument document) {


        try {


            createIndexIfNotExists();


            client.index(
                    i -> i
                            .index(INDEX)
                            .id(
                                    document.getCameraId()
                                            .toString()
                            )
                            .document(
                                    document
                            )
            );


        } catch (Exception e) {
            throw new RuntimeException("OpenSearch indexing failed", e);
        }

    }


    private void createIndexIfNotExists()
            throws Exception {


        boolean exists =
                client.indices()
                        .exists(
                                e -> e.index(INDEX)
                        )
                        .value();


        if (!exists) {


            client.indices()
                    .create(
                            c -> c
                                    .index(INDEX)
                                    .mappings(
                                            m -> m
                                                    .properties(
                                                            "cameraId",
                                                            p -> p.keyword(
                                                                    k -> k
                                                            )
                                                    )
                                                    .properties(
                                                            "name",
                                                            p -> p.text(
                                                                    t -> t
                                                            )
                                                    )
                                                    .properties(
                                                            "location",
                                                            p -> p.text(
                                                                    t -> t
                                                            )
                                                    )
                                                    .properties(
                                                            "createdAt",
                                                            p -> p.date(
                                                                    d -> d
                                                            )
                                                    )
                                    )
                    );
        }

    }

}
