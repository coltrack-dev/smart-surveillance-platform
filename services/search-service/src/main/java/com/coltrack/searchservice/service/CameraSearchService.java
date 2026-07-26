package com.coltrack.searchservice.service;


import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.repository.OpenSearchCameraSearchRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;


import java.util.List;


@Service
@RequiredArgsConstructor
public class CameraSearchService {


    private final OpenSearchCameraSearchRepository repository;


    public List<CameraDocument> search(String query) {

        return repository.search(query);

    }

}
