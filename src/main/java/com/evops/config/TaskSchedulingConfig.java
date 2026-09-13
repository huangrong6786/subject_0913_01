package com.evops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时调度。任务自身带开关（evops.task.feeding-post.enabled），
 * 测试环境关闭调度，改由测试直接触发任务方法，保证回归可重复。
 */
@Configuration
@EnableScheduling
public class TaskSchedulingConfig {
}
