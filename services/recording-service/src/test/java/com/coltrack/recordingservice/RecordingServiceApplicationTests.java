package com.coltrack.recordingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "recording.export.enabled=false",
        "recording.export.endpoint=http://localhost:9000",
        "recording.export.bucket=test-bucket",
        "recording.export.access-key=test-access-key",
        "recording.export.secret-key=test-secret-key",
        "recording.export.region=us-east-1",

        "recording.s3.enabled=false",
        "recording.s3.endpoint=http://localhost:9000",
        "recording.s3.bucket=test-bucket",
        "recording.s3.access-key=test-access-key",
        "recording.s3.secret-key=test-secret-key",
        "recording.s3.region=us-east-1"
})
class RecordingServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
