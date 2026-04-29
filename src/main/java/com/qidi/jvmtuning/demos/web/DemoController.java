package com.qidi.jvmtuning.demos.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * demo样例
 * @author maqidi
 * @version 1.0
 * @create 2026-04-29 10:28
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/hi")
    public String hi() throws InterruptedException {
        Thread.sleep(5000);
        return "hi";
    }
}
