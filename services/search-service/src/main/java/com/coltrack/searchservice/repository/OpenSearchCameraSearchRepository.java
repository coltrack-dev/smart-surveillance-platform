package com.coltrack.searchservice.repository;


import com.coltrack.searchservice.document.CameraDocument;

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


    public List<CameraDocument> search(String query) {

        try {

            SearchResponse<CameraDocument> response =
                    client.search(
                            s -> s
                                    .index(INDEX)
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


            return response.hits()
                    .hits()
                    .stream()
                    .map(hit -> hit.source())
                    .toList();


        } catch (Exception e) {


            throw new RuntimeException(
                    "OpenSearch search failed",
                    e
            );

        }

    }

}
