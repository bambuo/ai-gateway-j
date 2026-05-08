package ai.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private Server server = new Server();
    private String upstream = "https://api.anthropic.com";
    private Auth auth = new Auth();
    private OAuth oauth = new OAuth();
    private Identity identity = new Identity();
    private Map<String, Object> env = Map.of();
    private PromptEnv promptEnv = new PromptEnv();
    private ProcessConfig process = new ProcessConfig();
    private LoggingConfig logging = new LoggingConfig();

    public void validate() {
        if (identity == null || identity.getDeviceId() == null || identity.getDeviceId().contains("0000000000")) {
            throw new IllegalArgumentException("gateway.identity.device-id must be set to a real 64-char hex value");
        }
        if (auth == null || auth.getTokens() == null || auth.getTokens().isEmpty()) {
            throw new IllegalArgumentException("gateway.auth.tokens must have at least one entry");
        }
        if (oauth == null || oauth.getRefreshToken() == null || oauth.getRefreshToken().isBlank()) {
            throw new IllegalArgumentException("gateway.oauth.refresh-token is required");
        }
    }

    @Getter
    @Setter
    public static class Server {
        private int port = 8443;
    }

    @Getter
    @Setter
    public static class Auth {
        private List<TokenEntry> tokens = List.of();
    }

    @Getter
    @Setter
    public static class TokenEntry {
        private String name;
        private String token;
    }

    @Getter
    @Setter
    public static class OAuth {
        private String accessToken;
        private String refreshToken;
        private long expiresAt;
    }

    @Getter
    @Setter
    public static class Identity {
        private String deviceId;
        private String email;
    }

    @Getter
    @Setter
    public static class PromptEnv {
        private String platform;
        private String shell;
        private String osVersion;
        private String workingDir;
    }

    @Getter
    @Setter
    public static class ProcessConfig {
        private long constrainedMemory;
        private List<Long> rssRange = List.of(300_000_000L, 600_000_000L);
        private List<Long> heapTotalRange = List.of(400_000_000L, 700_000_000L);
        private List<Long> heapUsedRange = List.of(200_000_000L, 500_000_000L);
    }

    @Getter
    @Setter
    public static class LoggingConfig {
        private boolean audit = false;
    }
}
