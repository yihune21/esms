package et.com.cog.esms.core.security;

import et.com.cog.esms.core.identity.Delegation;
import et.com.cog.esms.core.identity.DelegationRepository;
import et.com.cog.esms.core.identity.WorkspaceMemberRepository;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider          tokenProvider;
    private final StringRedisTemplate       redisTemplate;
    private final DelegationRepository      delegationRepo;
    private final RoleRepository            roleRepo;
    private final WorkspaceMemberRepository memberRepo;

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

        String tokenType = tokenProvider.getTokenType(claims);
        if (!"access".equals(tokenType)) {
            filterChain.doFilter(request, response);
            return;
        }

        String jti = tokenProvider.getJti(claims);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(DENYLIST_PREFIX + jti))) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"type\":\"/errors/auth\",\"title\":\"Session revoked\",\"status\":401}");
            return;
        }

        UUID userId  = tokenProvider.getUserId(claims);
        String idleKey = IDLE_PREFIX + userId;
        String lastActivity = redisTemplate.opsForValue().get(idleKey);
        if (lastActivity != null) {
            redisTemplate.expire(idleKey, Duration.ofMinutes(5));
        }

        List<String> jwtPermissions = tokenProvider.getPermissions(claims);
        String roleCode = tokenProvider.getRoleCode(claims);
        UUID workspaceId = tokenProvider.getWorkspaceId(claims);

        List<String> effectivePermissions = new ArrayList<>(jwtPermissions);

        // Check database-level super admin
        boolean isDbSuperAdmin = false;
        try {
            isDbSuperAdmin = memberRepo.findByUserId(userId).stream()
                    .anyMatch(m -> "SUPER_ADMIN".equals(m.getRole().getCode()));
            if (isDbSuperAdmin) {
                roleRepo.findByCode("SUPER_ADMIN").ifPresent(superAdminRole -> {
                    List<String> superAdminCodes = superAdminRole.getPermissionCodes();
                    for (String code : superAdminCodes) {
                        if (!effectivePermissions.contains(code)) {
                            effectivePermissions.add(code);
                        }
                    }
                });
            }
        } catch (Exception ex) {
            log.warn("Super admin role lookup failed for userId={}: {}", userId, ex.getMessage());
        }

        String effectiveRoleCode = isDbSuperAdmin ? "SUPER_ADMIN" : roleCode;

        // Inject delegation-based CEO authorities if active
        boolean isDelegating = false;
        try {
            List<Delegation> activeDelegations = workspaceId != null
                    ? delegationRepo.findActiveForDelegate(userId, workspaceId, Instant.now())
                    : delegationRepo.findActiveForDelegateAnyWorkspace(userId, Instant.now());

            if (!activeDelegations.isEmpty()) {
                isDelegating = true;
                log.debug("User {} has {} active delegation(s) — injecting CEO authorities",
                        userId, activeDelegations.size());

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
            log.warn("Delegation authority injection failed for userId={}: {}", userId, ex.getMessage());
        }

        List<SimpleGrantedAuthority> authorities = effectivePermissions.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        if (effectiveRoleCode != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + effectiveRoleCode));
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        auth.setDetails(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);

        WorkspaceContext.set(workspaceId, userId, effectiveRoleCode, effectivePermissions, isDelegating);

        try {
            filterChain.doFilter(request, response);
        } finally {
            WorkspaceContext.clear();
        }
    }
}
