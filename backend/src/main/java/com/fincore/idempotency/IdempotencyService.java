package com.fincore.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fincore.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable idempotency backed by the {@code idempotency_keys} primary key.
 *
 * <p>{@link #claim} runs {@code INSERT ... ON CONFLICT DO NOTHING} in the caller's transaction:
 * <ul>
 *   <li>First request: the row is inserted (uncommitted) and the caller proceeds.</li>
 *   <li>Concurrent duplicate: PostgreSQL blocks the second INSERT on the unique index until the
 *       first transaction finishes. If it committed, the second sees the conflict and reads the
 *       stored result; if it rolled back, the second request claims the key and executes.</li>
 *   <li>Later duplicate: conflict, compare fingerprints, replay or reject with 409.</li>
 * </ul>
 * Because the key, the business change and its outcome commit atomically, there is no window in
 * which money moved but the key was not recorded (or vice versa).
 */
@Service
public class IdempotencyService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredKey(String scope, String fingerprint, UUID resourceId) {
    }

    /**
     * @return empty if this call claimed the key (caller must execute and then {@link #attach});
     *         otherwise the resource id of the original execution with the same payload.
     * @throws ApiException 409 if the key was used with a different payload or operation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> claim(String key, String scope, String fingerprint) {
        validateKey(key);
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (idempotency_key, scope, request_fingerprint, created_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, scope, fingerprint);
        if (inserted == 1) {
            return Optional.empty();
        }
        StoredKey stored = jdbc.queryForObject(
                "SELECT scope, request_fingerprint, resource_id FROM idempotency_keys WHERE idempotency_key = ?",
                (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)), key);
        if (!stored.scope().equals(scope) || !stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request payload");
        }
        if (stored.resourceId() == null) {
            // Cannot happen with a committed claim (resource is attached in the same transaction).
            throw new IllegalStateException("Idempotency key " + key + " has no resource attached");
        }
        return Optional.of(stored.resourceId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void attach(String key, UUID resourceId) {
        jdbc.update("UPDATE idempotency_keys SET resource_id = ? WHERE idempotency_key = ?", resourceId, key);
    }

    /** SHA-256 over a canonical, delimiter-separated representation of the request. */
    public static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            String s = part == null ? "" : part.toString();
            canonical.append(s.length()).append(':').append(s).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-100 characters of letters, digits, '.', '_', ':' or '-'");
        }
    }
}
