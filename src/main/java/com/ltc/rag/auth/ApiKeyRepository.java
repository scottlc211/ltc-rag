package com.ltc.rag.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * API Key 存取。只存 SHA256，明文永不落库。
 */
@Repository
public class ApiKeyRepository {

    public record KeyRow(long id, String name, String role, String status, Integer quotaPerDay,
                         List<String> allowedNamespaces) {
    }

    public record KeySummary(long id, String name, String keyPrefix, String role, String status,
                             Integer quotaPerDay, List<String> allowedNamespaces,
                             LocalDateTime createdAt, LocalDateTime lastUsedAt) {
    }

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KeyRow> findByHash(String hash) {
        List<KeyRow> rows = jdbc.query(
                "SELECT id, name, role, status, quota_per_day, allowed_namespaces FROM kb_api_key WHERE key_hash = ?",
                (rs, i) -> new KeyRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        (Integer) rs.getObject("quota_per_day"),
                        parseNamespaces(rs.getString("allowed_namespaces"))),
                hash);
        return rows.stream().findFirst();
    }

    public List<KeySummary> listAll() {
        return jdbc.query("""
                SELECT id, name, key_prefix, role, status, quota_per_day, allowed_namespaces, created_at, last_used_at
                FROM kb_api_key ORDER BY id DESC LIMIT 200
                """, (rs, i) -> new KeySummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("key_prefix"),
                rs.getString("role"),
                rs.getString("status"),
                (Integer) rs.getObject("quota_per_day"),
                parseNamespaces(rs.getString("allowed_namespaces")),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toLocalDateTime()));
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_api_key", Long.class);
        return n == null ? 0 : n;
    }

    public long insert(String name, String prefix, String hash, String role) {
        return insert(name, prefix, hash, role, null, List.of());
    }

    public long insert(String name, String prefix, String hash, String role,
                       Integer quotaPerDay, List<String> allowedNamespaces) {
        String nsCsv = joinNamespaces(allowedNamespaces);
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kb_api_key (name, key_prefix, key_hash, role, quota_per_day, allowed_namespaces) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, prefix);
            ps.setString(3, hash);
            ps.setString(4, role);
            if (quotaPerDay == null || quotaPerDay <= 0) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, quotaPerDay);
            }
            if (nsCsv == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, nsCsv);
            }
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public int updateKey(long id, String status, String role, List<String> allowedNamespaces) {
        String nsCsv = joinNamespaces(allowedNamespaces);
        return jdbc.update("""
                UPDATE kb_api_key
                   SET status = COALESCE(?, status),
                       role = COALESCE(?, role),
                       allowed_namespaces = ?
                 WHERE id = ?
                """, status, role, nsCsv, id);
    }

    public void touch(long id) {
        jdbc.update("UPDATE kb_api_key SET last_used_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public static List<String> parseNamespaces(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public static String joinNamespaces(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<String> cleaned = list.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }
}
