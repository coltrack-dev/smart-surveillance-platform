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


            client.index(
                    i -> i
                            .index(INDEX)
                            .id(
                                    document.getCameraId().toString()
                            )
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
