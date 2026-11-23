package com.ltc.rag.auth;

import com.ltc.rag.common.Digests;
import com.ltc.rag.config.RagProperties;
import com.ltc.rag.repo.QaLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 签发与校验。
 *
 * <p>明文人只会在签发时出现一次；库里存 SHA256，泄漏了也无法反推。
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** last_used_at 没必要每个请求都写库，5 分钟一次足够审计用。 */
    private static final long TOUCH_INTERVAL_MS = 5 * 60 * 1000L;

    private final ApiKeyRepository repository;
    private final QaLogRepository qaLogRepository;
    private final RagProperties props;
    private final Map<Long, Long> lastTouchAt = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository repository, QaLogRepository qaLogRepository, RagProperties props) {
        this.repository = repository;
        this.qaLogRepository = qaLogRepository;
        this.props = props;
    }

    /**
     * 签发新 Key，返回明文（调用方务必只保存这一次）。
     */
    public String issue(String name, String role) {
        return issue(name, role, null);
    }

    public String issue(String name, String role, Integer quotaPerDay) {
        String raw = "ltc_" + randomString(40);
        String normalizedRole = "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "AGENT";
        repository.insert(name, raw.substring(0, 12), sha256(raw), normalizedRole, quotaPerDay);
        return raw;
    }

    public Optional<AuthContext.Caller> authenticate(String rawKey) {
        return repository.findByHash(sha256(rawKey))
                .filter(row -> "ACTIVE".equalsIgnoreCase(row.status()))
                .map(row -> {
                    touchThrottled(row.id());
                    return new AuthContext.Caller(row.id(), row.name(), row.role(), row.quotaPerDay());
                });
    }

    /**
     * 检查该调用方今日是否已超出每日调用配额（quota_per_day 为 null 或 &lt;= 0 表示不限制）。
     */
    public boolean isQuotaExceeded(AuthContext.Caller caller) {
        if (caller == null || caller.quotaPerDay() == null || caller.quotaPerDay() <= 0) {
            return false;
        }
        return qaLogRepository.countTodayCalls(caller.keyId()) >= caller.quotaPerDay();
    }

    /**
     * 首次启动且表里没有任何 Key 时，自动生成一个 ADMIN Key 打到日志里。
     * 否则新部署的服务没有任何入口，得手动 INSERT。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdminKey() {
        if (!props.auth().enabled()) {
            log.warn("鉴权已关闭（rag.auth.enabled=false），仅限本机调试使用");
            return;
        }
        try {
            if (repository.count() > 0) {
                return;
            }
            String raw = issue("bootstrap-admin", "ADMIN");
            log.warn("""

                    ============================================================
                     已生成初始 ADMIN API Key（只显示这一次，请立即保存）：
                     {}
                    ============================================================
                    """, raw);
        } catch (RuntimeException e) {
            // 表还没建好时不应该让应用起不来，运维补建表后重启即可
            log.error("初始化 ADMIN Key 失败，请确认 kb_api_key 表已创建（参考 sql/schema.sql）", e);
        }
    }

    public static String sha256(String value) {
        return Digests.sha256Hex(value);
    }

    private void touchThrottled(long keyId) {
        long now = System.currentTimeMillis();
        Long last = lastTouchAt.get(keyId);
        if (last != null && now - last < TOUCH_INTERVAL_MS) {
            return;
        }
        lastTouchAt.put(keyId, now);
        try {
            repository.touch(keyId);
        } catch (RuntimeException e) {
            // 审计字段写失败不应该影响业务请求
            log.debug("更新 last_used_at 失败 keyId={}", keyId, e);
        }
    }

    private static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
