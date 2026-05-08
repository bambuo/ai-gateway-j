package ai.gateway.proxy;

import ai.gateway.auth.Authenticator;
import ai.gateway.config.GatewayProperties;
import ai.gateway.oauth.TokenManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyInit {

    private final GatewayProperties gatewayProperties;
    private final Authenticator authenticator;
    private final TokenManager tokenManager;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        authenticator.initTokens(gatewayProperties.getAuth().getTokens());
        tokenManager.init();

        log.info("AI 网关已就绪，监听 http://0.0.0.0:{}", gatewayProperties.getServer().getPort());
        log.info("上游地址: {}", gatewayProperties.getUpstream());
        log.info("已授权客户端: {}", String.join(", ", gatewayProperties.getAuth().getTokens().stream().map(GatewayProperties.TokenEntry::getName).toList()));
    }
}
