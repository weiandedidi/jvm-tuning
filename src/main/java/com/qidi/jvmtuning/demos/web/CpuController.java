package com.qidi.jvmtuning.demos.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试cpu飙升的方法
 *
 * @author maqidi
 * @version 1.0
 * @create 2026-04-28 20:04
 */
@RestController
@RequestMapping("/cpu")
public class CpuController {

    /**
     * 测试死循环代码
     *
     * @return
     */
    @GetMapping("/high")
    public String highCpu() {
        new Thread(() -> {
            while (true) {
                // 空转，疯狂占用CPU
            }
        }, "high-cpu-thread").start();

        return "ok";
    }
}
