package ai.gateway;

import ai.gateway.config.GatewayProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        log.info("AI 网关启动中...");

        var ctx = SpringApplication.run(GatewayApplication.class, args);
        var props = ctx.getBean(GatewayProperties.class);

        props.validate();

        log.info("标准设备 ID: {}...", props.getIdentity().getDeviceId().substring(0, 8));
        log.info("已授权客户端: {}", String.join(", ", props.getAuth().getTokens().stream().map(GatewayProperties.TokenEntry::getName).toList()));
    }
}
