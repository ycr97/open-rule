package io.openrule.api.test;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 测试用最小启动类。置于 io.openrule.api.test，不扫描 io.openrule.api（控制器只经 autoconfig @Bean 注册）。 */
@SpringBootApplication
public class ApiTestApplication {
}
