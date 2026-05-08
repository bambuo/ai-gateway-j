package ai.gateway.rewriter;

import ai.gateway.config.GatewayProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BodyRewriter {

    private static final RandomGenerator RNG = RandomGenerator.getDefault();
    private static final Pattern REMINDER_PATTERN = Pattern.compile("(<system-reminder>)([\\s\\S]*?)(</system-reminder>)");
    private static final Pattern CC_VERSION_PATTERN = Pattern.compile("cc_version=[\\d.]+\\.[a-f0-9]{3}");
    private static final Pattern PLATFORM_PATTERN = Pattern.compile("Platform:\\s*\\S+");
    private static final Pattern SHELL_PATTERN = Pattern.compile("Shell:\\s*\\S+");
    private static final Pattern OS_VERSION_PATTERN = Pattern.compile("OS Version:\\s*[^\n<]+");
    private static final Pattern WORKING_DIR_PATTERN = Pattern.compile("((?:Primary )?[Ww]orking directory:\\s*)/\\S+");
    private static final Pattern HOME_PATH_PATTERN = Pattern.compile("/(?:Users|home)/[^/\\s]+/");
    private final ObjectMapper mapper;
    private final GatewayProperties gatewayProperties;

    public BodyRewriter(ObjectMapper mapper, GatewayProperties gatewayProperties) {
        this.mapper = mapper;
        this.gatewayProperties = gatewayProperties;
    }

    public byte[] rewrite(@Nullable byte[] rawBody, String path) {
        if (rawBody == null || rawBody.length == 0) {
            return rawBody;
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        }
        catch (Exception e) {
            return rawBody;
        }
        if (root instanceof ObjectNode on) {
            var rewritten = switch (classify(path)) {
                case MESSAGES -> rewriteMessages(on);
                case EVENT_BATCH -> rewriteEventBatch(on);
                case GENERIC -> rewriteGeneric(on);
                case UNKNOWN -> on;
            };
            try {
                return mapper.writeValueAsBytes(rewritten);
            }
            catch (Exception e) {
                log.warn("重写后的请求体序列化失败: {}", e.getMessage());
            }
        }
        return rawBody;
    }

    private static TargetType classify(@NonNull String path) {
        if (path.startsWith("/v1/messages")) {
            return TargetType.MESSAGES;
        }
        if (path.contains("/event_logging/batch")) {
            return TargetType.EVENT_BATCH;
        }
        if (path.contains("/policy_limits") || path.contains("/settings")) {
            return TargetType.GENERIC;
        }
        return TargetType.UNKNOWN;
    }

    private ObjectNode rewriteMessages(@NonNull ObjectNode body) {
        var metadata = body.get("metadata");
        if (metadata instanceof ObjectNode mn && mn.has("user_id")) {
            try {
                var userId = mapper.readTree(mn.get("user_id").asText());
                if (userId instanceof ObjectNode uon) {
                    uon.put("device_id", gatewayProperties.getIdentity().getDeviceId());
                    mn.put("user_id", mapper.writeValueAsString(userId));
                    log.debug("已重写 metadata.user_id 中的 device_id");
                }
            }
            catch (Exception e) {
                log.warn("解析 metadata.user_id 失败: {}", e.getMessage());
            }
        }
        rewriteSystemReminders(body);
        var firstUserText = extractFirstUserMessage(body);
        var version = String.valueOf(gatewayProperties.getEnv().getOrDefault("version", "2.1.81"));
        var hash = !firstUserText.isEmpty() ? ClaudeCodeHash.compute(firstUserText, version) : ClaudeCodeHash.fallbackHash();
        log.debug("CCH 计算完成: {}（消息长度 {} 字符）", hash, firstUserText.length());
        stripBillingHeader(body);
        rewriteSystemPrompt(body, hash);
        return body;
    }

    private ObjectNode rewriteEventBatch(@NonNull ObjectNode body) {
        var events = body.get("events");
        if (events == null || !events.isArray()) {
            return body;
        }
        for (var event : events) {
            if (!(event instanceof ObjectNode en && en.has("event_data"))) {
                continue;
            }
            var data = en.get("event_data");
            if (!(data instanceof ObjectNode dn)) {
                continue;
            }

            if (dn.has("device_id")) {
                dn.put("device_id", gatewayProperties.getIdentity().getDeviceId());
            }
            if (dn.has("email")) {
                dn.put("email", gatewayProperties.getIdentity().getEmail());
            }
            if (dn.has("env")) {
                dn.set("env", buildCanonicalEnv());
            }
            if (dn.has("process")) {
                rewriteProcess(dn);
            }
            dn.remove("baseUrl");
            dn.remove("base_url");
            dn.remove("gateway");
            if (dn.has("additional_metadata")) {
                rewriteAdditionalMetadata(dn);
            }

            log.debug("已重写事件: {}", dn.has("event_name") ? dn.get("event_name").asText() : "unknown");
        }
        return body;
    }

    private ObjectNode rewriteGeneric(@NonNull ObjectNode body) {
        if (body.has("device_id")) {
            body.put("device_id", gatewayProperties.getIdentity().getDeviceId());
        }
        if (body.has("email")) {
            body.put("email", gatewayProperties.getIdentity().getEmail());
        }
        return body;
    }

    private void rewriteSystemReminders(@NonNull ObjectNode body) {
        var messages = body.get("messages");
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (var msg : messages) {
            if (msg instanceof ObjectNode mn && mn.has("content")) {
                var content = mn.get("content");
                if (content.isTextual()) {
                    mn.put("content", rewriteReminderText(content.asText()));
                }
                else if (content.isArray()) {
                    for (var block : content) {
                        if (block instanceof ObjectNode bn && bn.has("text")) {
                            bn.put("text", rewriteReminderText(bn.get("text").asText()));
                        }
                    }
                }
            }
        }
    }

    private String extractFirstUserMessage(@NonNull ObjectNode body) {
        var messages = body.get("messages");
        if (messages == null || !messages.isArray()) {
            return "";
        }
        for (var msg : messages) {
            if (msg instanceof ObjectNode mn && "user".equals(mn.get("role").asText())) {
                var content = mn.get("content");
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
                if (content != null && content.isArray()) {
                    for (var block : content) {
                        if (block instanceof ObjectNode bn && "text".equals(bn.get("type").asText()) && bn.has("text")) {
                            return bn.get("text").asText();
                        }
                    }
                }
            }
        }
        return "";
    }

    private void stripBillingHeader(@NonNull ObjectNode body) {
        if (!body.has("system")) {
            return;
        }
        var system = body.get("system");
        if (system.isArray()) {
            var filtered = mapper.createArrayNode();
            for (var item : system) {
                var text = item.isTextual() ? item.asText() : item.has("text") ? item.get("text").asText() : "";
                if (!text.trim().startsWith("x-anthropic-billing-header:")) {
                    filtered.add(item);
                }
                else {
                    log.debug("已从系统提示中剥离计费头");
                }
            }
            body.set("system", filtered);
        }
        else if (system.isTextual()) {
            body.put("system", system.asText().replaceAll("x-anthropic-billing-header:[^\n]*\n?", ""));
        }
    }

    private void rewriteSystemPrompt(@NonNull ObjectNode body, String hash) {
        if (!body.has("system")) {
            return;
        }
        var system = body.get("system");
        if (system.isArray()) {
            for (int i = 0; i < system.size(); i++) {
                var item = system.get(i);
                if (item.isTextual()) {
                    ((ArrayNode) system).set(i, mapper.getNodeFactory().textNode(rewritePromptText(item.asText(), hash)));
                }
                else if (item instanceof ObjectNode on && on.has("text")) {
                    on.put("text", rewritePromptText(on.get("text").asText(), hash));
                }
            }
        }
        else if (system.isTextual()) {
            body.put("system", rewritePromptText(system.asText(), hash));
        }
    }

    private ObjectNode buildCanonicalEnv() {
        var env = gatewayProperties.getEnv();
        if (env == null) {
            return mapper.createObjectNode();
        }
        var out = mapper.createObjectNode();

        var safeFalseFields = Set.of("is_ci", "is_github_action", "is_claude_code_remote", "is_running_with_bun", "is_local_agent_mode", "is_conductor", "is_claubbit");

        env.forEach((k, v) -> {
            if (safeFalseFields.contains(k)) {
                out.put(k, false);
            }
            else if (v instanceof String s) {
                out.put(k, s);
            }
            else if (v instanceof Boolean b) {
                out.put(k, b);
            }
            else if (v instanceof Number n) {
                out.put(k, n.intValue());
            }
        });

        return out;
    }

    private void rewriteProcess(@NonNull ObjectNode dn) {
        var proc = dn.get("process");
        if (proc.isTextual()) {
            try {
                var decoded = mapper.readTree(Base64.getDecoder().decode(proc.asText()));
                if (decoded instanceof ObjectNode pon) {
                    dn.put("process", Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(rewriteProcessFields(pon))));
                }
            }
            catch (Exception ignored) {
            }
        }
        else if (proc instanceof ObjectNode pon) {
            dn.set("process", rewriteProcessFields(pon));
        }
    }

    private void rewriteAdditionalMetadata(ObjectNode dn) {
        try {
            var decoded = mapper.readTree(Base64.getDecoder().decode(dn.get("additional_metadata").asText()));
            if (decoded instanceof ObjectNode aon) {
                aon.remove("baseUrl");
                aon.remove("base_url");
                aon.remove("gateway");
                dn.put("additional_metadata", Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(aon)));
            }
        }
        catch (Exception ignored) {
        }
    }

    private @NonNull String rewriteReminderText(String text) {
        var matcher = REMINDER_PATTERN.matcher(text);
        var sb = new StringBuffer();
        while (matcher.find()) {
            var rewritten = rewritePromptText(matcher.group(2), null);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("<system-reminder>" + rewritten + "</system-reminder>"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String rewritePromptText(String text, @Nullable String hash) {
        var pe = gatewayProperties.getPromptEnv();
        if (pe == null) {
            return text;
        }
        var result = text;
        if (hash != null) {
            result = CC_VERSION_PATTERN.matcher(result).replaceAll("cc_version=" + gatewayProperties.getEnv().getOrDefault("version", "2.1.81") + "." + hash);
        }
        result = PLATFORM_PATTERN.matcher(result).replaceAll("Platform: " + pe.getPlatform());
        result = SHELL_PATTERN.matcher(result).replaceAll("Shell: " + pe.getShell());
        result = OS_VERSION_PATTERN.matcher(result).replaceAll("OS Version: " + pe.getOsVersion());
        result = WORKING_DIR_PATTERN.matcher(result).replaceAll("$1" + pe.getWorkingDir());

        var m = HOME_PATH_PATTERN.matcher(pe.getWorkingDir());
        var prefix = m.find() ? m.group() : "/Users/user/";
        result = HOME_PATH_PATTERN.matcher(result).replaceAll(prefix);

        return result;
    }

    private ObjectNode rewriteProcessFields(ObjectNode proc) {
        var p = gatewayProperties.getProcess();
        if (p == null) {
            return proc;
        }
        var out = proc.deepCopy();
        out.put("constrainedMemory", p.getConstrainedMemory());
        out.put("rss", randomInRange(p.getRssRange()));
        out.put("heapTotal", randomInRange(p.getHeapTotalRange()));
        out.put("heapUsed", randomInRange(p.getHeapUsedRange()));
        return out;
    }

    private static long randomInRange(@Nullable List<Long> range) {
        if (range == null || range.size() < 2) {
            return 0;
        }
        return RNG.nextLong(range.get(0), range.get(1));
    }

    private enum TargetType {MESSAGES, EVENT_BATCH, GENERIC, UNKNOWN}
}
