package com.coltrack.cameraservice.service;


import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;


@Slf4j
@Service
public class RtspStreamChecker {


    private final ExecutorService executor =
            Executors.newCachedThreadPool();


    // TODO: Rework camera stream health monitoring.
    // FFmpegFrameGrabber should not be used for periodic stream status checks.
    // Implement persistent RTSP stream monitoring with last frame timestamp tracking.
    public boolean check(String url) {

        Future<Boolean> future =
                executor.submit(() -> checkInternal(url));

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {

            future.cancel(true);

            log.error("RTSP timeout: {}", url);

            return false;

        } catch (Exception e) {

            return false;
        }

    }



    private boolean checkInternal(String url) {

        FFmpegFrameGrabber grabber = null;

        try {
            grabber = new FFmpegFrameGrabber(url);

            grabber.setOption("stimeout", "5000000");

            grabber.start();

            return grabber.grabImage() != null;


        } catch(Exception e) {

            log.error("RTSP failed {}", url, e);

            return false;


        } finally {


            if (grabber != null) {

                try {
                    grabber.stop();
                }
                catch(Exception ignored){}

            }
        }
    }
}
