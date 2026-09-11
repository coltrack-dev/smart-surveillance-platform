package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.RtspUrlFormat;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CameraCredentialsTest {

    private final CameraCredentialsCipher cipher = new CameraCredentialsCipher(
            Base64.getEncoder().encodeToString(new byte[32])
    );

    @Test
    void encryptsAndDecryptsPassword() {
        String encrypted = cipher.encrypt("secret-password");

        assertNotEquals("secret-password", encrypted);
        assertEquals("secret-password", cipher.decrypt(encrypted));
    }

    @Test
    void resolvesXmTemplateWithoutPersistingPlaintextPassword() {
        CameraEntity camera = CameraEntity.builder()
                .rtspUrl("rtsp://192.168.50.27:554/"
                        + "user={username}_password={password}_channel=8_stream=1.sdp?real_stream")
                .rtspUsername("rt")
                .rtspPasswordEncrypted(cipher.encrypt("test-password"))
                .rtspUrlFormat(RtspUrlFormat.XM)
                .build();

        String resolved = new RtspUrlResolver(cipher).resolve(camera);

        assertEquals(
                "rtsp://192.168.50.27:554/"
                        + "user=rt_password=test-password_channel=8_stream=1.sdp?real_stream",
                resolved
        );
    }
}
