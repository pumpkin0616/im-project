package com.example.im;

import com.example.im.config.ImProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// 告诉Spring Boot 这是应用入口，扫描当前包下的Controller、Service和Configuration。- 自动配置HTTP服务器、数据库和Spring Security。
@SpringBootApplication 
// 把 application.yml 中的配置读取成Java对象。
@EnableConfigurationProperties(ImProperties.class)
/** 允许项目执行定时任务，例如：
- Outbox发布。
- 分片路由刷新。
- 生命周期清理。
- 数据库分区维护。 */
@EnableScheduling
public class ImApplication {
// main() 是普通Java程序的入口：
    public static void main(String[] args) {
        SpringApplication.run(ImApplication.class, args);
    }
}

