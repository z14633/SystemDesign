package com.h3c.controller;


import com.h3c.entity.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;

@RestController
@RequestMapping("/user")
public class UserController {

    /**
     * 登录
     */
    @PostMapping("/login")
    public String login(HttpSession session) {

        User user = new User(1L, "zhangsan");

        // Session 存 Redis
        session.setAttribute("user", user);

        return "login success";
    }

    /**
     * 获取当前用户
     */
    @GetMapping("/current")
    public Object currentUser(HttpSession session) {

        Object user = session.getAttribute("user");

        if (user == null) {
            return "not login";
        }

        return user;
    }
}