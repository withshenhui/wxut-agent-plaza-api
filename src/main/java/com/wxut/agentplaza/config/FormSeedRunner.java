package com.wxut.agentplaza.config;

import com.wxut.agentplaza.service.form.FormAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 智能填报：启动时种子预置模板（幂等，表非空则跳过）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormSeedRunner implements CommandLineRunner {

    private final FormAgentService formAgentService;

    @Override
    public void run(String... args) {
        formAgentService.ensureSeed();
    }
}
