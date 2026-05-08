package ai.gateway.oauth;

import ai.gateway.config.GatewayProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
public class TokenManager {

    private static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final String SCOPES = "user:inference user:profile user:sessions:claude_code user:mcp_servers user:file_upload";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private final String refreshToken;
    private volatile @Nullable String accessToken;
    private volatile Instant expiresAt;

    public TokenManager(HttpClient httpClient, ObjectMapper mapper, GatewayProperties config) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        var oauth = config.getOauth();
        this.refreshToken = oauth.getRefreshToken();
        this.accessToken = oauth.getAccessToken();
        this.expiresAt = oauth.getExpiresAt() > 0 ? Instant.ofEpochMilli(oauth.getExpiresAt()) : Instant.EPOCH;

        log.info("TokenManager 初始化完成，令牌过期时间: {}", expiresAt != Instant.EPOCH ? expiresAt.toString() : "未知（首次请求时按需刷新）");
    }

    public void init() {
        var now = Instant.now();
        var fiveMinutes = Duration.ofMinutes(5);

        if (accessToken != null && expiresAt.isAfter(now.plus(fiveMinutes))) {
            var remaining = Duration.between(now, expiresAt).toMinutes();
            log.info("使用已有访问令牌（{} 分钟后过期）", remaining);
            return;
        }
        if (accessToken != null) {
            log.info("访问令牌即将过期 — 首次请求时按需刷新");
        }
        else {
            log.info("未缓存访问令牌 — 首次请求时按需刷新");
        }
    }

    public boolean isTokenValid() {
        return accessToken != null && !Instant.now().isAfter(expiresAt);
    }

    public @Nullable String getAccessToken() {
        if (accessToken == null) {
            log.info("未找到访问令牌，正在按需刷新...");
            try {
                refresh();
                return accessToken;
            }
            catch (Exception e) {
                log.error("OAuth 刷新失败: {}", e.getMessage());
                return null;
            }
        }
        if (Instant.now().isAfter(expiresAt)) {
            log.warn("访问令牌已过期，正在按需刷新...");
            try {
                refresh();
                return accessToken;
            }
            catch (Exception e) {
                log.error("OAuth 刷新失败: {}", e.getMessage());
                return null;
            }
        }
        return accessToken;
    }

    public synchronized void refresh() {
        try {
            var body = """
                    {
                      "grant_type": "refresh_token",
                      "refresh_token": "%s",
                      "client_id": "%s",
                      "scope": "%s"
                    }
                    """.formatted(refreshToken, CLIENT_ID, SCOPES);

            var request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body)).build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("OAuth refresh failed (" + response.statusCode() + "): " + response.body());
            }

            var json = mapper.readTree(response.body());
            this.accessToken = json.get("access_token").asText();
            this.expiresAt = Instant.now().plusSeconds(json.has("expires_in") ? json.get("expires_in").asLong() : 3600L);
            log.debug("OAuth 令牌已刷新，过期时间 {}", expiresAt);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("OAuth refresh interrupted", e);
        }
        catch (Exception e) {
            throw new RuntimeException("OAuth refresh failed: " + e.getMessage(), e);
        }
    }
}
