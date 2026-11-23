package com.ltc.rag.auth;

import com.ltc.rag.common.ApiException;

/**
 * 当前请求的调用方上下文。
 *
 * <p>ThreadLocal 在 Servlet 线程模型下足够；Filter 的 finally 负责清理，
 * 避免线程池复用导致身份串号。
 */
public final class AuthContext {

    public record Caller(long keyId, String name, String role, Integer quotaPerDay) {

        public Caller(long keyId, String name, String role) {
            this(keyId, name, role, null);
        }

        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    private static final ThreadLocal<Caller> CALLER = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void bind(Caller caller, String requestId) {
        CALLER.set(caller);
        REQUEST_ID.set(requestId);
    }

    public static Caller caller() {
        return CALLER.get();
    }

    public static Caller requireCaller() {
        Caller c = CALLER.get();
        if (c == null) {
            throw ApiException.unauthorized("缺少调用方身份");
        }
        return c;
    }

    /** 入库/删除这类写操作只允许 ADMIN，避免任意 Agent 污染知识库。 */
    public static void requireAdmin() {
        Caller c = requireCaller();
        if (!c.isAdmin()) {
            throw ApiException.forbidden("该操作需要 ADMIN 角色的 API Key");
        }
    }

    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static void clear() {
        CALLER.remove();
        REQUEST_ID.remove();
    }
}
