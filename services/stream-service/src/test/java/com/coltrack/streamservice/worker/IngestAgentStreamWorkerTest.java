package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.VideoProcessingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestAgentStreamWorkerTest {

    @Test
    void mapsExistingVideoModesToAgentContract() {
        assertEquals(
                "COPY",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.COPY)
        );
        assertEquals(
                "H264",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.TRANSCODE_H264)
        );
        assertEquals(
                "AUTO",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.AUTO)
        );
    }
}
