package com.coltrack.searchservice.dto;


import com.coltrack.searchservice.document.CameraDocument;

import java.util.List;


public record CameraSearchResult(

        List<CameraDocument> content,

        int page,

        int size,

        long total

) {
}
