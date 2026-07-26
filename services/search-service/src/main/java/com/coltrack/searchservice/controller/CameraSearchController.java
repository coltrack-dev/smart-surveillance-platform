package com.coltrack.searchservice.controller;


import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.service.CameraSearchService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class CameraSearchController {


    private final CameraSearchService service;


    @GetMapping("/cameras")
    public Page<CameraDocument> searchCameras(

            @RequestParam(required = false)
            String q,

            @RequestParam(required = false)
            String location,

            Pageable pageable

    ) {

        return service.search(
                q,
                location,
                pageable
        );
    }

}
