package com.mungai_stack.securityex.controllers;

import com.mungai_stack.securityex.model.Users;
import com.mungai_stack.securityex.services.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path="/users")
public class UserController {
    @Autowired
    private UserService userService;
    @PostMapping("/register")
    public ResponseEntity<Users> registerUser(@RequestBody Users user){
      Users newUser=userService.registerUser(user);
      return ResponseEntity.ok(newUser);
    }
    @PostMapping("/login")
    public String login(@RequestBody Users user){
        System.out.println(user);
        return userService.verify(user);
    }
}
