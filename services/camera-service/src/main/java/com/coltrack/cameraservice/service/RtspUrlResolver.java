package com.coltrack.cameraservice.service;

import com.coltrack.cameraservice.entity.CameraEntity;
import com.coltrack.cameraservice.entity.RtspUrlFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class RtspUrlResolver {

    private final CameraCredentialsCipher credentialsCipher;

    public String resolve(CameraEntity camera) {
        String template = camera.getRtspUrl();
        if (!camera.isCredentialsConfigured()) {
            return template;
        }

        String username = encode(camera.getRtspUsername());
        String password = encode(credentialsCipher.decrypt(camera.getRtspPasswordEncrypted()));

        if (template.contains("{username}") || template.contains("{password}")) {
            return template
                    .replace("{username}", username)
                    .replace("{password}", password);
        }

        if (camera.getRtspUrlFormat() == RtspUrlFormat.STANDARD) {
            int schemeEnd = template.indexOf("://");
            if (schemeEnd < 0) {
                throw new IllegalArgumentException("Invalid RTSP URL template");
            }
            return template.substring(0, schemeEnd + 3)
                    + username + ":" + password + "@"
                    + template.substring(schemeEnd + 3);
        }

        throw new IllegalArgumentException(
                "XM RTSP URL must contain {username} and {password} placeholders"
        );
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("_", "%5F");
    }
}
