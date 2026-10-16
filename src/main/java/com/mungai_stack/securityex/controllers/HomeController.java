package com.mungai_stack.securityex.controllers;


import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {
    @GetMapping("/greet")
    public String greet(HttpServletRequest request){

        return "Welcome Home Simon"+request.getSession().getId();
    }
}
