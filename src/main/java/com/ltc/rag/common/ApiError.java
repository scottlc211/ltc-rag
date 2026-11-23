package com.ltc.rag.common;

/**
 * 统一错误体。requestId 让调用方能在日志里对齐一次失败请求的全链路。
 */
public record ApiError(String code, String message, String requestId) {
}
