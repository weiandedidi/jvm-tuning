package com.qidi.jvmtuning.demos.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试内存泄漏的方法
 *
 * @author maqidi
 * @version 1.0
 * @create 2026-04-28 20:33
 */
@RestController
@RequestMapping("/mem")
public class MemoryLeakController {

    private static final List<byte[]> CACHE = new ArrayList<>();

    @GetMapping("/leak")
    public String leak() {
        CACHE.add(new byte[1024 * 1024]); // 每次1MB
        return "size=" + CACHE.size();
    }
}
