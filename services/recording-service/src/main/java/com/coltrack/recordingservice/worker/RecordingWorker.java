package com.coltrack.recordingservice.worker;

import com.coltrack.recordingservice.model.RecordingSession;
import com.coltrack.recordingservice.model.RecordingStatus;
import com.coltrack.recordingservice.service.RecordingStorageService;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;


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
                            buildCommand(outputFile)
                    )
                            .redirectErrorStream(true)
                            .start();


            session.setFfmpegProcess(
                    process
            );


            /*
             * Give FFmpeg some time to connect RTSP.
             */
            Thread.sleep(2000);


            if (!process.isAlive()) {

                throw new IllegalStateException(
                        "FFmpeg terminated immediately"
                );
            }


            session.setStatus(
                    RecordingStatus.RECORDING
            );


            session.setStartedAt(
                    Instant.now()
            );


            listener.started(
                    session
            );


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


                    stopFfmpeg(
                            process
                    );


                    break;
                }


                Thread.sleep(1000);
            }



            int exitCode =
                    process.waitFor();



            if (session.isStopRequested()) {


                log.info(
                        "Recording stopped camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );


                session.setStatus(
                        RecordingStatus.STOPPED
                );


                listener.stopped(
                        session
                );


            } else {


                log.warn(
                        "FFmpeg finished unexpectedly camera={} exitCode={}",
                        session.getCameraId(),
                        exitCode
                );


                session.setStatus(
                        RecordingStatus.FAILED
                );


                listener.failed(
                        session
                );
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


            listener.failed(
                    session
            );


        }
        finally {


            if (process != null && process.isAlive()) {


                log.warn(
                        "Force stopping FFmpeg camera={}",
                        session.getCameraId()
                );


                stopFfmpeg(
                        process
                );
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
     * Graceful FFmpeg shutdown.
     *
     * SIGINT allows FFmpeg to write MP4 metadata (moov atom).
     */
    private void stopFfmpeg(Process process) {

        try {

            log.info(
                    "Sending graceful stop to FFmpeg pid={}",
                    process.pid()
            );


            // SIGTERM
            process.destroy();


            if (!process.waitFor(
                    10,
                    TimeUnit.SECONDS
            )) {


                log.warn(
                        "FFmpeg did not terminate gracefully pid={}",
                        process.pid()
                );


                process.destroyForcibly();


                process.waitFor();
            }


            log.info(
                    "FFmpeg stopped pid={} alive={}",
                    process.pid(),
                    process.isAlive()
            );


        } catch (Exception e) {


            log.error(
                    "Failed stopping FFmpeg",
                    e
            );


            process.destroyForcibly();
        }
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

                "-hide_banner",

                "-loglevel",
                "warning",

                "-rtsp_transport",
                "tcp",

                "-i",
                rtspUrl,

                "-c",
                "copy",

                "-movflags",
                "+faststart",

                "-f",
                "mp4",

                "-y",

                outputFile.toString()
        );
    }
}
