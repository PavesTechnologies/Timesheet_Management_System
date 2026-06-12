package com.intranet.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public")
public class PublicController {

    @GetMapping("/hello")
    public String hello() {
        return "Welcome to the Intranet Time Sheet Management System of Paves Technologies!";
    }
}
