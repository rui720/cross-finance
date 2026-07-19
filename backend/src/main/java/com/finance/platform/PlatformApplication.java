package com.finance.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 跨境金融业财核算与智能决策平台启动类
 * <p>
 * 排除 UserDetailsServiceAutoConfiguration：本项目用 JWT + 自定义用户表，
 * 无需 Spring Security 默认的 {noop} 用户，避免启动警告与默认密码泄露。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
@EnableScheduling
@MapperScan("com.finance.platform.**.mapper")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
