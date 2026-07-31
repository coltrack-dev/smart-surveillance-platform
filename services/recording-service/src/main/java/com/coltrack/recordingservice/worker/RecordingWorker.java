package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingStorageService;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;


@Slf4j
public class RecordingWorker implements Runnable {

    private final RecordingSession session;
    private final RecordingStorageService storageService;
    private final String rtspUrl;
    private final RecordingListener listener;

    public RecordingWorker(
            RecordingSession session,
            RecordingStorageService storageService,
            String rtspUrl,
            RecordingListener listener
    ) {

        this.session = session;
        this.storageService = storageService;
        this.rtspUrl = rtspUrl;
        this.listener = listener;
    }

    @Override
    public void run() {

        Process process = null;

        log.info(
                "Recording worker started camera={}",
                session.getCameraId()
        );

        try {

            Path directory =
                    storageService.createRecordingDirectory(
                            session.getCameraId()
                    );


            storageService.cleanupDirectory(
                    directory
            );


            Path outputFile =
                    directory.resolve(
                            "recording-" +
                                    Instant.now().toEpochMilli() +
                                    ".mp4"
                    );


            log.info(
                    "Starting FFmpeg recording camera={} file={}",
                    session.getCameraId(),
                    outputFile
            );


            process =
                    new ProcessBuilder(
                            buildCommand(
                                    outputFile
                            )
                    )
                            .redirectErrorStream(true)
                            .start();


            session.setFfmpegProcess(
                    process
            );


            session.setStatus(
                    RecordingStatus.RECORDING
            );


            session.setStartedAt(
                    Instant.now()
            );

            listener.started(session);

            log.info(
                    "FFmpeg recording started camera={} pid={}",
                    session.getCameraId(),
                    process.pid()
            );


            while (process.isAlive()) {


                if (session.isStopRequested()) {

                    log.info(
                            "Recording stop requested camera={}",
                            session.getCameraId()
                    );

                    process.destroyForcibly();

                    break;
                }


                Thread.sleep(1000);
            }


            int exitCode =
                    process.waitFor();


            if (session.isStopRequested()) {


                log.info(
                        "Recording stopped manually camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );


                session.setStatus(
                        RecordingStatus.STOPPED
                );

                listener.stopped(session);

            } else {


                log.warn(
                        "FFmpeg recording finished unexpectedly camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );


                session.setStatus(
                        RecordingStatus.FAILED
                );

                listener.failed(session);
            }


            session.setFinishedAt(
                    Instant.now()
            );


        }
        catch (Exception e) {


            session.setStatus(
                    RecordingStatus.FAILED
            );


            log.error(
                    "Recording failed camera={}",
                    session.getCameraId(),
                    e
            );

        }
        finally {


            if (process != null && process.isAlive()) {

                log.info(
                        "Destroying FFmpeg process camera={}",
                        session.getCameraId()
                );


                process.destroyForcibly();
            }


            session.setFfmpegProcess(
                    null
            );
        }


        log.info(
                "Recording worker finished camera={}",
                session.getCameraId()
        );
    }



    /**
     * FFmpeg command:
     *
     * RTSP
     *   |
     *   v
     * FFmpeg
     *   |
     *   v
     * MP4
     */
    private List<String> buildCommand(
            Path outputFile
    ) {


        return List.of(

                "ffmpeg",

                "-rtsp_transport",
                "tcp",

                "-i",
                rtspUrl,

                "-c",
                "copy",

                "-movflags",
                "+faststart",

                "-y",

                outputFile.toString()
        );
    }
}
