package com.coltrack.searchservice.service;


import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.repository.OpenSearchCameraRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class CameraSearchService {


    private final OpenSearchCameraRepository repository;



    public Page<CameraDocument> search(
            String q,
            String location,
            Pageable pageable
    ) {


        if (q == null && location == null) {
            return repository.findAll(pageable);
        }


        // дальше добавим bool query:
        // must multi_match(q)
        // filter term(location)


        return repository.findAll(pageable);
    }
}
