package com.coltrack.searchservice.repository;


import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.dto.CameraSearchResult;

import lombok.RequiredArgsConstructor;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;

import org.springframework.stereotype.Repository;


import java.util.List;


@Repository
@RequiredArgsConstructor
public class OpenSearchCameraSearchRepository {


    private static final String INDEX = "cameras";


    private final OpenSearchClient client;


    public CameraSearchResult search(
            String query,
            int page,
            int size
    ) {

        try {

            SearchResponse<CameraDocument> response =
                    client.search(
                            s -> s
                                    .index(INDEX)

                                    .from(
                                            page * size
                                    )

                                    .size(size)

                                    .query(
                                            q -> q
                                                    .multiMatch(
                                                            m -> m
                                                                    .query(query)
                                                                    .fields(
                                                                            "name",
                                                                            "location"
                                                                    )
                                                    )
                                    ),

                            CameraDocument.class
                    );


            List<CameraDocument> cameras =
                    response.hits()
                            .hits()
                            .stream()
                            .map(hit -> hit.source())
                            .toList();


            long total =
                    response.hits()
                            .total()
                            .value();


            return new CameraSearchResult(
                    cameras,
                    page,
                    size,
                    total
            );


        } catch (Exception e) {

            throw new RuntimeException(
                    "Camera search failed",
                    e
            );

        }

    }

}