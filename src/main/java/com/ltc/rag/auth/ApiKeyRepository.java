package com.ltc.rag.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API Key 存取。只存 SHA256，明文永不落库。
 */
@Repository
public class ApiKeyRepository {

    public record KeyRow(long id, String name, String role, String status, Integer quotaPerDay) {
    }

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KeyRow> findByHash(String hash) {
        List<KeyRow> rows = jdbc.query(
                "SELECT id, name, role, status, quota_per_day FROM kb_api_key WHERE key_hash = ?",
                (rs, i) -> new KeyRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getString("status"),
                        (Integer) rs.getObject("quota_per_day")),
                hash);
        return rows.stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_api_key", Long.class);
        return n == null ? 0 : n;
    }

    public long insert(String name, String prefix, String hash, String role) {
        return insert(name, prefix, hash, role, null);
    }

    public long insert(String name, String prefix, String hash, String role, Integer quotaPerDay) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kb_api_key (name, key_prefix, key_hash, role, quota_per_day) VALUES (?, ?, ?, ?, ?)",
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
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void touch(long id) {
        jdbc.update("UPDATE kb_api_key SET last_used_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }
}
