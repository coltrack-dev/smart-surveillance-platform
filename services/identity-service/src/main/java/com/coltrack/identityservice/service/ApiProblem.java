package com.coltrack.identityservice.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ApiProblem extends ResponseStatusException {
    public ApiProblem(int status, String reason) { super(HttpStatus.valueOf(status), reason); }
}
