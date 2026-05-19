package et.com.cog.esms.core.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * JWT issuance and validation.
 * Claims include userId, workspaceId, roleCode, and permissions[].
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final String issuer;

    public JwtTokenProvider(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.access-token-ttl}") Duration accessTokenTtl,
            @Value("${app.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl,
            @Value("${app.security.jwt.issuer}") String issuer) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.issuer = issuer;
    }

    /**
     * Issue a pre-auth token (before OTP verification).
     * Contains only the userId — no workspace or permissions.
     */
    public String createPreAuthToken(UUID userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(userId.toString())
                .claim("username", username)
                .claim("type", "pre_auth")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(180))) // 3 min
                .signWith(signingKey)
                .compact();
    }

    /**
     * Issue a full access token after OTP verification.
     */
    public String createAccessToken(UUID userId, String username,
                                     UUID workspaceId, String roleCode,
                                     List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(userId.toString())
                .claim("username", username)
                .claim("type", "access")
                .claim("workspace_id", workspaceId != null ? workspaceId.toString() : null)
                .claim("role", roleCode)
                .claim("permissions", permissions)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Issue a refresh token (long-lived, stored in HttpOnly cookie).
     */
    public String createRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(userId.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate a token. Returns null if invalid/expired.
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return null;
        }
    }

    public UUID getUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public String getUsername(Claims claims) {
        return claims.get("username", String.class);
    }

    public String getTokenType(Claims claims) {
        return claims.get("type", String.class);
    }

    public UUID getWorkspaceId(Claims claims) {
        String wsId = claims.get("workspace_id", String.class);
        return wsId != null ? UUID.fromString(wsId) : null;
    }

    public String getRoleCode(Claims claims) {
        return claims.get("role", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(Claims claims) {
        Object perms = claims.get("permissions");
        if (perms instanceof List<?>) {
            return (List<String>) perms;
        }
        return Collections.emptyList();
    }

    public String getJti(Claims claims) {
        return claims.getId();
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }
}
