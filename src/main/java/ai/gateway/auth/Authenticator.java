package ai.gateway.auth;

import ai.gateway.config.GatewayProperties.TokenEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class Authenticator {

    private final Map<String, TokenEntry> tokenMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        tokenMap.clear();
    }

    public void initTokens(@NonNull List<TokenEntry> tokens) {
        tokenMap.clear();
        for (var entry : tokens) {
            tokenMap.put(entry.getToken(), entry);
            log.debug("已注册客户端: {}", entry.getName());
        }
    }

    public @Nullable String authenticate(@Nullable String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            var entry = tokenMap.get(apiKey);
            if (entry != null) {
                return entry.getName();
            }
        }
        return null;
    }
}
