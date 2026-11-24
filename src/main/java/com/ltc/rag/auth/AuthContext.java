package com.ltc.rag.auth;

import com.ltc.rag.common.ApiException;

import java.util.List;
import java.util.Locale;

/**
 * 当前请求的调用方上下文（含角色与命名空间权限范围）。
 *
 * <p>ThreadLocal 在 Servlet 线程模型下足够；Filter 的 finally 负责清理，
 * 避免线程池复用导致身份串号。
 */
public final class AuthContext {

    public record Caller(long keyId, String name, String role, Integer quotaPerDay, List<String> allowedNamespaces) {

        public Caller(long keyId, String name, String role) {
            this(keyId, name, role, null, List.of());
        }

        public Caller(long keyId, String name, String role, Integer quotaPerDay) {
            this(keyId, name, role, quotaPerDay, List.of());
        }

        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }

        /** 是否受命名空间范围限制（ADMIN 永远不受限；AGENT 若配置了 allowedNamespaces 则受限）。 */
        public boolean isNamespaceRestricted() {
            return !isAdmin() && allowedNamespaces != null && !allowedNamespaces.isEmpty();
        }

        public boolean canAccessNamespace(String ns) {
            if (!isNamespaceRestricted()) {
                return true;
            }
            if (ns == null || ns.isBlank()) {
                return false;
            }
            String norm = ns.strip().toLowerCase(Locale.ROOT);
            return allowedNamespaces.contains(norm);
        }

        public void assertCanAccessNamespace(String ns) {
            if (!canAccessNamespace(ns)) {
                throw ApiException.forbidden("当前角色无权访问命名空间：" + ns);
            }
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

    /** 入库/删除/改配置这类写操作只允许 ADMIN，避免只读角色污染知识库。 */
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
