package com.planbridge.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * v2: PostgreSQL + ddl-auto:update 사용으로 테이블 자동 생성.
 * Oracle 전용 수동 DDL 제거됨.
 */
@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("PlanBridge v2 API 서버 초기화 완료 (PostgreSQL)");
    }
}
