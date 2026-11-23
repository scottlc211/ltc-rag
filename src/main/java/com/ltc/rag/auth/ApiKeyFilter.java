package com.ltc.rag.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ltc.rag.common.ApiError;
import com.ltc.rag.config.RagProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * API Key 鉴权过滤器。
 *
 * <p>支持两种写法，覆盖绝大多数 Agent 框架的默认习惯：
 * <pre>
 *   X-API-Key: ltc_xxx
 *   Authorization: Bearer ltc_xxx
 * </pre>
 *
 * <p>每个请求生成 requestId 并回写 X-Request-Id，方便跨系统对账。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final RagProperties props;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApiKeyService apiKeyService, RagProperties props, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")) {
            return true;
        }
        // 静态前端（/、/index.html、favicon）不鉴权，否则页面自己都打不开
        if (!path.startsWith("/v1/")) {
            return true;
        }
        // 健康检查给探活用，也不需要 Key
        return "/v1/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        response.setHeader("X-Request-Id", requestId);

        try {
            if (!props.auth().enabled()) {
                AuthContext.bind(new AuthContext.Caller(0L, "anonymous", "ADMIN"), requestId);
                chain.doFilter(request, response);
                return;
            }

            String rawKey = extractKey(request);
            if (rawKey == null || rawKey.isBlank()) {
                writeUnauthorized(response, requestId, "缺少 API Key（X-API-Key 或 Authorization: Bearer）");
                return;
            }

            var caller = apiKeyService.authenticate(rawKey);
            if (caller.isEmpty()) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                        requestId, "API Key 无效或已停用");
                return;
            }

            // 对消耗 Token 的问答与异步任务提交接口执行每日配额检查
            if (isQuotaGuardedRequest(request) && apiKeyService.isQuotaExceeded(caller.get())) {
                writeError(response, 429, "QUOTA_EXCEEDED",
                        requestId, "该 API Key 今日调用次数已达上限（" + caller.get().quotaPerDay() + " 次/天）");
                return;
            }

            AuthContext.bind(caller.get(), requestId);
            chain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private static String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    private static boolean isQuotaGuardedRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return "/v1/qa".equals(path)
                || "/v1/qa/stream".equals(path)
                || "/v1/tasks".equals(path)
                || "/v1/chat/completions".equals(path);
    }

    private void writeUnauthorized(HttpServletResponse response, String requestId, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", requestId, message);
    }

    private void writeError(HttpServletResponse response, int status, String code,
                            String requestId, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), new ApiError(code, message, requestId));
    }
}
