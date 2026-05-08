package ai.gateway.proxy;

import ai.gateway.auth.Authenticator;
import ai.gateway.config.GatewayProperties;
import ai.gateway.oauth.TokenManager;
import ai.gateway.rewriter.BodyRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ProxyFilter implements Filter {

    private static final Pattern BEARER_PATTERN = Pattern.compile("^Bearer\\s+(.+)$");

    private final GatewayProperties gatewayProperties;
    private final Authenticator authenticator;
    private final TokenManager tokenManager;
    private final BodyRewriter bodyRewriter;
    private final HeaderRewriter headerRewriter;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {

        var req = (HttpServletRequest) servletRequest;
        var res = (HttpServletResponse) servletResponse;
        var path = req.getRequestURI();
        var method = req.getMethod();

        if (path.equals("/_health") || path.equals("/_verify")) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        log.info("\u2190 {} {} 来自 {}", method, path, req.getRemoteAddr());

        var clientName = authenticateRequest(req);
        if (clientName == null) {
            sendJson(res, 401, Map.of("error", "Unauthorized - provide client token via x-api-key header"));
            log.warn("未授权请求: {} {}", method, path);
            return;
        }

        var oauthToken = tokenManager.getAccessToken();
        if (oauthToken == null) {
            sendJson(res, 503, Map.of("error", "OAuth token not available - gateway is refreshing"));
            log.error("无有效 OAuth 令牌可用");
            return;
        }

        log.info("客户端 \"{}\" \u2192 {} {}", clientName, method, path);

        var bodyBytes = readBody(req);
        if (bodyBytes.length > 0) {
            try {
                bodyBytes = bodyRewriter.rewrite(bodyBytes, path);
            }
            catch (Exception e) {
                log.error("请求体重写失败 {}: {}", path, e.getMessage());
            }
        }

        try {
            proxyToUpstream(req, res, method, path, bodyBytes, oauthToken, clientName);
        }
        catch (Exception e) {
            log.error("代理转发错误 {} {}: {}", method, path, e.getMessage());
            if (!res.isCommitted()) {
                sendJson(res, 502, Map.of("error", "Bad gateway", "detail", e.getMessage()));
            }
        }
    }

    private @Nullable String authenticateRequest(@NonNull HttpServletRequest req) {
        var apiKey = req.getHeader("x-api-key");
        if (apiKey != null) {
            var name = authenticator.authenticate(apiKey);
            if (name != null) {
                return name;
            }
        }
        var clientToken = extractBearerToken(req.getHeader("proxy-authorization"));
        if (clientToken != null) {
            var name = authenticator.authenticate(clientToken);
            if (name != null) {
                return name;
            }
        }
        clientToken = extractBearerToken(req.getHeader("authorization"));
        if (clientToken != null) {
            return authenticator.authenticate(clientToken);
        }
        return null;
    }

    private void sendJson(@NonNull HttpServletResponse res, int status, Map<String, Object> body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        try (var writer = res.getWriter()) {
            objectMapper.writeValue(writer, body);
        }
    }

    private static byte[] readBody(@NonNull HttpServletRequest req) throws IOException {
        if (req.getContentLength() <= 0 && req.getContentType() == null) {
            return new byte[0];
        }
        try (var is = req.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private void proxyToUpstream(@NonNull HttpServletRequest req, HttpServletResponse res, String method, String path, byte[] bodyBytes, String oauthToken, String clientName) throws Exception {

        var upstreamUrl = gatewayProperties.getUpstream() + path;
        if (req.getQueryString() != null) {
            upstreamUrl += "?" + req.getQueryString();
        }

        var originalHeaders = extractHeaders(req);
        originalHeaders.remove("content-length");
        var rewrittenHeaders = headerRewriter.rewrite(originalHeaders);
        rewrittenHeaders.put("x-api-key", oauthToken);
        rewrittenHeaders.put("content-length", String.valueOf(bodyBytes.length));

        var bodyPublisher = bodyBytes.length > 0 ? HttpRequest.BodyPublishers.ofByteArray(bodyBytes) : HttpRequest.BodyPublishers.noBody();

        var requestBuilder = HttpRequest.newBuilder().uri(URI.create(upstreamUrl)).method(method, bodyPublisher);

        rewrittenHeaders.forEach(requestBuilder::header);

        var upstreamResponse = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

        res.setStatus(upstreamResponse.statusCode());
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (!"transfer-encoding".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                values.forEach(v -> res.addHeader(name, v));
            }
        });

        try (var upstreamStream = upstreamResponse.body(); var outputStream = res.getOutputStream()) {
            upstreamStream.transferTo(outputStream);
            outputStream.flush();
        }

        if (gatewayProperties.getLogging() != null && gatewayProperties.getLogging().isAudit()) {
            log.info("[审计] 客户端={} {} {} \u2192 {}", clientName, method, path, upstreamResponse.statusCode());
        }
    }

    private static @Nullable String extractBearerToken(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        var matcher = BEARER_PATTERN.matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> extractHeaders(@NonNull HttpServletRequest req) {
        var headers = new LinkedHashMap<String, String>();
        var names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            var name = names.nextElement();
            headers.put(name, req.getHeader(name));
        }
        return headers;
    }
}
