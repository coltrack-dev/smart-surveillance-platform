package com.coltrack.cameraservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class TestController {

    private static final Logger log =
            LoggerFactory.getLogger(TestController.class);


    @GetMapping("/test")
    public String test() {

        log.info("Test request received");

        return "OK";
    }
}
