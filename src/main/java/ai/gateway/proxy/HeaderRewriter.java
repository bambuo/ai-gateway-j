package ai.gateway.proxy;

import ai.gateway.config.GatewayProperties;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class HeaderRewriter {

    private static final Set<String> HOP_BY_HOP = Set.of("host", "connection", "proxy-authorization", "proxy-connection", "transfer-encoding", "authorization", "x-api-key");

    private static final Set<String> STRIP_HEADERS = Set.of("x-anthropic-billing-header");

    private final GatewayProperties gatewayProperties;

    public SequencedMap<String, String> rewrite(@NonNull Map<String, String> originalHeaders) {
        var out = new LinkedHashMap<String, String>();

        for (var entry : originalHeaders.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            var lower = key.toLowerCase();

            if (HOP_BY_HOP.contains(lower)) {
                continue;
            }
            if (STRIP_HEADERS.contains(lower)) {
                continue;
            }

            if ("user-agent".equals(lower)) {
                out.put(key, "claude-code/" + getVersion() + " (external, cli)");
            }
            else {
                out.put(key, value);
            }
        }

        return out;
    }

    private String getVersion() {
        var env = gatewayProperties.getEnv();
        return env != null ? String.valueOf(env.getOrDefault("version", "2.1.81")) : "2.1.81";
    }
}
