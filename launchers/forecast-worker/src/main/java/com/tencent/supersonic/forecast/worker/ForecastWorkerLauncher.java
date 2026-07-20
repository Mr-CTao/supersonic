package com.tencent.supersonic.forecast.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 出入库预测独立 Worker 启动器。
 *
 * <p>
 * 该进程不启动 HTTP Server，仅加载 Forecast 服务、元数据库 Mapper 和定时调度。在线 Standalone 的
 * {@code forecast.worker.enabled} 保持关闭，因此源库扫描与预测计算不会占用请求线程。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "com.tencent.supersonic.forecast",
        exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class})
@EnableScheduling
public class ForecastWorkerLauncher {

    /**
     * 启动无 Web 的 Forecast Worker。
     *
     * @param args Spring Boot 命令行参数。
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ForecastWorkerLauncher.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
