package com.coltrack.searchservice.repository;


import com.coltrack.searchservice.document.CameraDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;


@Slf4j
@Repository
@RequiredArgsConstructor
public class OpenSearchCameraRepository {


    private static final String INDEX = "cameras";


    private final OpenSearchClient client;



    public void save(CameraDocument document) {

        try {

            client.index(i -> i
                    .index(INDEX)
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



    public Page<CameraDocument> findAll(Pageable pageable) {


        try {

            SearchResponse<CameraDocument> response =
                    client.search(s -> s
                                    .index(INDEX)
                                    .from((int) pageable.getOffset())
                                    .size(pageable.getPageSize()),
                            CameraDocument.class
                    );


            List<CameraDocument> content =
                    response.hits()
                            .hits()
                            .stream()
                            .map(Hit::source)
                            .toList();



            long total =
                    response.hits()
                            .total()
                            .value();


            return new PageImpl<>(
                    content,
                    pageable,
                    total
            );


        } catch (Exception e) {

            throw new RuntimeException(
                    "OpenSearch search failed",
                    e
            );
        }
    }
}
