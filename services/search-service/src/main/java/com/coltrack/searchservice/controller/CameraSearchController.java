package com.coltrack.searchservice.controller;


import com.coltrack.searchservice.dto.CameraSearchResult;
import com.coltrack.searchservice.service.CameraSearchService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class CameraSearchController {


    private final CameraSearchService service;


    @GetMapping("/cameras")
    public CameraSearchResult search(

            @RequestParam String q,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size

    ) {


        return service.search(
                q,
                page,
                size
        );

    }

}
