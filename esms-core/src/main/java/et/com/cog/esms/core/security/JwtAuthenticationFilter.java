package et.com.cog.esms.core.security;

import et.com.cog.esms.core.identity.Delegation;
import et.com.cog.esms.core.identity.DelegationRepository;
import et.com.cog.esms.core.workspace.RoleRepository;
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
import java.time.Instant;
import java.util.ArrayList;
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
 * 6. [Option B] Injects extra authorities from any active Delegation records
 *    for this user in the current workspace — no re-login required.
 *    The injected authorities are in-memory only; the JWT is never modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider      tokenProvider;
    private final StringRedisTemplate   redisTemplate;
    private final DelegationRepository  delegationRepo;
    private final RoleRepository        roleRepo;

    private static final String BEARER_PREFIX  = "Bearer ";
    private static final String DENYLIST_PREFIX = "jwt:deny:";
    private static final String IDLE_PREFIX     = "session:idle:";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token  = header.substring(BEARER_PREFIX.length());
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
        UUID userId  = tokenProvider.getUserId(claims);
        String idleKey = IDLE_PREFIX + userId;
        String lastActivity = redisTemplate.opsForValue().get(idleKey);
        if (lastActivity != null) {
            // Touch the idle timer — reset to 5 min
            redisTemplate.expire(idleKey, Duration.ofMinutes(5));
        }

        // ── Base authorities from the JWT ─────────────────────────
        List<String> jwtPermissions = tokenProvider.getPermissions(claims);
        String roleCode = tokenProvider.getRoleCode(claims);
        UUID workspaceId = tokenProvider.getWorkspaceId(claims);

        List<String> effectivePermissions = new ArrayList<>(jwtPermissions);

        // ── Option B: per-request delegation injection ────────────
        //
        // Query the delegation table to find any active (non-revoked, within
        // time window) delegation where this user is the toUserId.
        // If found, look up the CEO role's permissions and add them to this
        // request's authority list without touching the JWT.
        //
        // This means:
        //   - No re-login needed when a delegation starts or is revoked.
        //   - The next request automatically reflects the current delegation state.
        //   - The injected authorities exist only in memory for this request's
        //     SecurityContext thread — they are never written back to the token.
        boolean isDelegating = false;
        try {
            List<Delegation> activeDelegations = workspaceId != null
                    ? delegationRepo.findActiveForDelegate(userId, workspaceId, Instant.now())
                    : delegationRepo.findActiveForDelegateAnyWorkspace(userId, Instant.now());

            if (!activeDelegations.isEmpty()) {
                isDelegating = true;
                log.debug("User {} has {} active delegation(s) — injecting CEO authorities",
                        userId, activeDelegations.size());

                // Fetch the CEO role's permission codes and merge them in,
                // avoiding duplicates that are already in the JWT.
                roleRepo.findByCode("CEO").ifPresent(ceoRole -> {
                    List<String> ceoCodes = ceoRole.getPermissionCodes();
                    for (String code : ceoCodes) {
                        if (!effectivePermissions.contains(code)) {
                            effectivePermissions.add(code);
                        }
                    }
                });
            }
        } catch (Exception ex) {
            // Never let a delegation lookup failure block the request.
            // Log it and continue with the base JWT authorities.
            log.warn("Delegation authority injection failed for userId={}: {}", userId, ex.getMessage());
        }

        // ── Build Spring Security authorities ─────────────────────
        List<SimpleGrantedAuthority> authorities = effectivePermissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        if (roleCode != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // ── Populate WorkspaceContext ──────────────────────────────
        WorkspaceContext.set(workspaceId, userId, roleCode, effectivePermissions, isDelegating);

        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
