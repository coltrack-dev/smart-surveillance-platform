package com.coltrack.searchservice.controller;


import com.coltrack.searchservice.document.CameraDocument;
import com.coltrack.searchservice.service.CameraSearchService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class CameraSearchController {


    private final CameraSearchService service;



    @GetMapping("/cameras")
    public List<CameraDocument> search(
            @RequestParam String q
    ) {

        return service.search(q);

    }

}
