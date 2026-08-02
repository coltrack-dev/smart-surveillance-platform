package com.coltrack.recordingservice.service;

import com.coltrack.recordingservice.model.RecordingSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@Slf4j
public class FfprobeService {

    public void fillMetadata(
            RecordingSession session,
            Path file
    ) {

        try {

            Process process =
                    new ProcessBuilder(
                            "ffprobe",
                            "-v",
                            "error",
                            "-select_streams",
                            "v:0",
                            "-show_entries",
                            "stream=codec_name,width,height,r_frame_rate",
                            "-of",
                            "default=noprint_wrappers=1",
                            file.toString()
                    ).start();

            String text =
                    new String(
                            process.getInputStream().readAllBytes()
                    );

            process.waitFor();

            for (String line : text.split("\n")) {

                if (line.startsWith("codec_name=")) {
                    session.setCodec(line.substring(11));
                }

                if (line.startsWith("width=")) {
                    session.setWidth(
                            Integer.parseInt(line.substring(6))
                    );
                }

                if (line.startsWith("height=")) {
                    session.setHeight(
                            Integer.parseInt(line.substring(7))
                    );
                }

                if (line.startsWith("r_frame_rate=")) {

                    String[] p =
                            line.substring(13).split("/");

                    if (p.length == 2) {

                        session.setFps(
                                Integer.parseInt(p[0]) /
                                        Integer.parseInt(p[1])
                        );
                    }
                }
            }

        } catch (Exception e) {

            log.warn("Unable to read ffprobe metadata", e);
        }
    }
}
