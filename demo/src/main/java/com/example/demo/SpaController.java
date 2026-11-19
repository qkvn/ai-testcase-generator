package com.example.demo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Các đường dẫn của giao diện React (VD: /admin) -> trả về index.html để React tự hiển thị trang. */
@Controller
public class SpaController {

    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "forward:/index.html";
    }
}
