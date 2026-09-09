package com.coltrack.streamservice.worker;

import com.coltrack.streamservice.model.VideoProcessingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestAgentStreamWorkerTest {

    @Test
    void mapsExistingVideoModesToAgentContract() {
        assertEquals(
                "COPY",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.COPY, "H264")
        );
        assertEquals(
                "H264",
                IngestAgentStreamWorker.agentVideoMode(
                        VideoProcessingMode.TRANSCODE_H264,
                        "COPY"
                )
        );
        assertEquals(
                "H264",
                IngestAgentStreamWorker.agentVideoMode(VideoProcessingMode.AUTO, "h264")
        );
    }

    @Test
    void rejectsUnsupportedAutoModeBeforeCallingAgent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IngestAgentStreamWorker.agentVideoMode(
                        VideoProcessingMode.AUTO,
                        "AUTO"
                )
        );
    }
}
