package com.qidi.jvmtuning.demos.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试GC频繁或停顿过长
 *
 * @author maqidi
 * @version 1.0
 * @create 2026-04-29 11:34
 */
@RestController
@RequestMapping("/gc")
public class GcController {

    @GetMapping("/storm")
    public String gcStorm() {
        for (int i = 0; i < 10000; i++) {
            byte[] data = new byte[1024 * 100]; // 100KB
        }
        return "ok";
    }
}
