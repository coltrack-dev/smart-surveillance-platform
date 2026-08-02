package com.coltrack.recordingservice.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FfmpegProcessCleaner {

    @PostConstruct
    public void cleanup() {

        log.info("Checking orphan ffmpeg processes...");

        ProcessHandle.allProcesses()
                .filter(this::isFfmpeg)
                .forEach(process -> {

                    log.warn(
                            "Found orphan ffmpeg process pid={}",
                            process.pid()
                    );

                    process.destroy();

                    try {

                        if (process.isAlive()) {

                            Thread.sleep(3000);

                            if (process.isAlive()) {

                                log.warn(
                                        "Force killing orphan ffmpeg pid={}",
                                        process.pid()
                                );

                                process.destroyForcibly();
                            }
                        }

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();
                    }
                });
    }


    private boolean isFfmpeg(ProcessHandle process) {

        return process.info()
                .command()
                .map(command ->
                        command.contains("ffmpeg")
                )
                .orElse(false);
    }
}
