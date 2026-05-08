package ai.gateway.oauth;

import ai.gateway.config.GatewayProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private static final long FIVE_MINUTES_MS = 5 * 60 * 1000L;

    private final TokenManager tokenManager;
    private final GatewayProperties gatewayProperties;

    @Scheduled(fixedDelay = FIVE_MINUTES_MS, initialDelay = FIVE_MINUTES_MS)
    public void scheduledRefresh() {
        if (tokenManager.isTokenValid() && gatewayProperties.getOauth().getExpiresAt() > 0) {
            var expiry = Instant.ofEpochMilli(gatewayProperties.getOauth().getExpiresAt());
            var remaining = Duration.between(Instant.now(), expiry);
            if (remaining.toMinutes() > 5) {
                log.debug("令牌仍有效（剩余 {} 分钟），跳过刷新", remaining.toMinutes());
                return;
            }
            log.info("令牌将在约 {} 分钟后过期，正在刷新...", Math.max(0, remaining.toMinutes()));
        }
        try {
            log.info("自动刷新 OAuth 令牌...");
            tokenManager.refresh();
            log.info("OAuth 令牌刷新成功");
        }
        catch (Exception e) {
            log.debug("OAuth 刷新推迟 — 将在下次请求时重试: {}", e.getMessage());
        }
    }
}
