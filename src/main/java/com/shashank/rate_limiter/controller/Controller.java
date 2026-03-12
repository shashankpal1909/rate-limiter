package com.shashank.rate_limiter.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @GetMapping("/api/resource")
    public ResponseEntity<Void> endpoint() {
        return new ResponseEntity<>(HttpStatus.OK);
    }

}
