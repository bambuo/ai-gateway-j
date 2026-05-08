package ai.gateway.proxy;

import ai.gateway.config.GatewayProperties;
import ai.gateway.oauth.TokenManager;
import ai.gateway.rewriter.BodyRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final GatewayProperties gatewayProperties;
    private final TokenManager tokenManager;
    private final BodyRewriter bodyRewriter;
    private final ObjectMapper mapper;

    @GetMapping("/_health")
    public ResponseEntity<Map<String, Object>> health() {
        var token = tokenManager.getAccessToken();
        var ok = token != null;

        var body = new LinkedHashMap<String, Object>();
        body.put("status", ok ? "ok" : "degraded");
        body.put("oauth", ok ? "valid" : "expired/refreshing");
        body.put("canonical_device", gatewayProperties.getIdentity().getDeviceId().substring(0, 8) + "...");
        body.put("upstream", gatewayProperties.getUpstream());
        body.put("clients", gatewayProperties.getAuth().getTokens().stream().map(GatewayProperties.TokenEntry::getName).toList());

        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @GetMapping("/_verify")
    public ResponseEntity<Map<String, Object>> verify() {
        return ResponseEntity.ok(buildVerificationPayload());
    }

    private @NonNull Map<String, Object> buildVerificationPayload() {
        var before = new LinkedHashMap<String, Object>();

        var beforeUserId = Map.of("device_id", "REAL_DEVICE_ID_FROM_CLIENT_abc123", "account_uuid", "shared-account-uuid", "session_id", "session-xxx");
        before.put("metadata.user_id", beforeUserId);
        before.put("billing_header", "x-anthropic-billing-header: cc_version=2.1.81.a1b; cc_entrypoint=cli;");
        before.put("system_prompt_env", """
                Here is useful information about the environment:
                <env>
                Working directory: /home/bob/myproject
                Platform: linux
                Shell: bash
                OS Version: Linux 6.5.0-generic
                </env>""");
        before.put("system_block_count", 2);

        var sampleInput = Map.of("metadata", Map.of("user_id", "{\"device_id\":\"REAL_DEVICE_ID_FROM_CLIENT_abc123\",\"account_uuid\":\"shared-account-uuid\",\"session_id\":\"session-xxx\"}"), "system", new Object[]{Map.of("type", "text", "text", "x-anthropic-billing-header: cc_version=2.1.81.a1b; cc_entrypoint=cli;"), Map.of("type", "text", "text", """
                Here is useful information about the environment:
                <env>
                Working directory: /home/bob/myproject
                Platform: linux
                Shell: bash
                OS Version: Linux 6.5.0-generic
                </env>""")}, "messages", new Object[]{Map.of("role", "user", "content", "hello")});

        try {
            var rewrittenBytes = bodyRewriter.rewrite(mapper.writeValueAsBytes(sampleInput), "/v1/messages");
            var rewritten = mapper.readTree(rewrittenBytes);

            var after = new LinkedHashMap<String, Object>();
            if (rewritten.has("metadata") && rewritten.get("metadata").has("user_id")) {
                after.put("metadata.user_id", mapper.readTree(rewritten.get("metadata").get("user_id").asText()));
            }
            after.put("billing_header", "(stripped)");
            if (rewritten.has("system") && rewritten.get("system").isArray()) {
                var sysArray = rewritten.get("system");
                after.put("system_prompt_env", sysArray.size() > 0 ? sysArray.get(0).has("text") ? sysArray.get(0).get("text").asText() : sysArray.get(0).asText() : "(empty)");
                after.put("system_block_count", sysArray.size());
            }

            return Map.of("_info", "This shows how the gateway rewrites a sample request", "before", before, "after", after);
        }
        catch (Exception e) {
            log.error("验证请求负载构建失败: {}", e.getMessage());
            return Map.of("error", "Failed to build verification payload");
        }
    }
}
