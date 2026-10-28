package com.unirag.common.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 启用计划任务（UploadSessionJanitor 的 @Scheduled 载体）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // @Scheduled(fixedRate) 已足够；保留 SchedulingConfigurer 作为统一配置入口
    }
}
