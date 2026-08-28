package com.coltrack.cameraservice.dto;

import java.util.UUID;
import com.coltrack.cameraservice.entity.RtspUrlFormat;
import com.coltrack.cameraservice.entity.VideoProcessingMode;

public record CreateCameraRequest(

        String name,

        UUID lbsLocationId,

        UUID categoryId,

        String rtspUrl,

        String rtspUsername,

        String rtspPassword,

        RtspUrlFormat rtspUrlFormat,

        VideoProcessingMode videoProcessingMode,

        boolean autoStart

) {
}
