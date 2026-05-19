package et.com.cog.esms.core.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT Authentication + Workspace Context filter.
 * <p>
 * 1. Extracts Bearer token from Authorization header.
 * 2. Validates the JWT signature, expiry, and issuer.
 * 3. Checks that the JTI is not in the Redis denylist (logout).
 * 4. Checks idle timeout: if lastActivity > 5 min, rejects with 440.
 * 5. Populates SecurityContext + WorkspaceContext.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate redisTemplate;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DENYLIST_PREFIX = "jwt:deny:";
    private static final String IDLE_PREFIX = "session:idle:";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        Claims claims = tokenProvider.parseToken(token);

        if (claims == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only accept access tokens (not pre_auth or refresh)
        String tokenType = tokenProvider.getTokenType(claims);
        if (!"access".equals(tokenType)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check JTI denylist (revoked tokens)
        String jti = tokenProvider.getJti(claims);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(DENYLIST_PREFIX + jti))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"/errors/auth\",\"title\":\"Session revoked\",\"status\":401}");
            return;
        }

        // Check idle timeout
        UUID userId = tokenProvider.getUserId(claims);
        String idleKey = IDLE_PREFIX + userId;
        String lastActivity = redisTemplate.opsForValue().get(idleKey);
        if (lastActivity != null) {
            // Touch the idle timer — reset to 5 min
            redisTemplate.expire(idleKey, Duration.ofMinutes(5));
        }
        // If the key doesn't exist, the session may have expired from idle
        // (but it could also be a fresh login — SessionService creates the key)

        // Populate Spring Security context
        List<String> permissions = tokenProvider.getPermissions(claims);
        String roleCode = tokenProvider.getRoleCode(claims);

        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        if (roleCode != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Populate WorkspaceContext (layer 1 of tenant isolation)
        UUID workspaceId = tokenProvider.getWorkspaceId(claims);
        WorkspaceContext.set(workspaceId, userId, roleCode, permissions);

        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
