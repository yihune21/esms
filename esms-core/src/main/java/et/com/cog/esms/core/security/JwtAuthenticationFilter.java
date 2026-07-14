package et.com.cog.esms.core.security;

import et.com.cog.esms.core.identity.Delegation;
import et.com.cog.esms.core.identity.DelegationRepository;
import et.com.cog.esms.core.identity.WorkspaceMemberRepository;
import et.com.cog.esms.core.workspace.RoleRepository;
import et.com.cog.esms.core.workspace.WorkspaceRepository;
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


@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider          tokenProvider;
    private final StringRedisTemplate       redisTemplate;
    private final DelegationRepository      delegationRepo;
    private final RoleRepository            roleRepo;
    private final WorkspaceMemberRepository memberRepo;
    private final WorkspaceRepository       workspaceRepo;

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

        boolean isDbSuperAdmin = "SUPER_ADMIN".equals(roleCode);
        try {
            if (!isDbSuperAdmin) {
                isDbSuperAdmin = memberRepo.findByUserId(userId).stream()
                        .anyMatch(m -> "SUPER_ADMIN".equals(m.getRole().getCode()));
            }
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

        // A deactivated workspace must lock out every one of its members
        // immediately, not just on their next login — this runs on every
        // request, so an already-issued access token stops working the moment
        // the workspace is suspended. /auth/logout is exempt so a locked-out
        // user can still cleanly end their session.
        boolean exemptFromLockout = request.getRequestURI().endsWith("/auth/logout");
        if (!exemptFromLockout && workspaceId != null && !"SUPER_ADMIN".equals(effectiveRoleCode)) {
            boolean suspended = workspaceRepo.findById(workspaceId)
                    .map(w -> !"ACTIVE".equals(w.getStatus()))
                    .orElse(false);
            if (suspended) {
                response.setStatus(403);
                response.setContentType("application/problem+json");
                response.getWriter().write(
                        "{\"type\":\"/errors/auth\",\"status\":403,\"code\":\"WORKSPACE_DEACTIVATED\","
                                + "\"title\":\"Your workspace has been deactivated. Contact your administrator.\"}");
                return;
            }
        }

        boolean isDelegating = false;
        try {
            List<Delegation> activeDelegations = workspaceId != null
                    ? delegationRepo.findActiveForDelegate(userId, workspaceId, Instant.now())
                    : delegationRepo.findActiveForDelegateAnyWorkspace(userId, Instant.now());

            if (!activeDelegations.isEmpty()) {
                isDelegating = true;

                // A delegation is a time-boxed transfer of the delegator's OWN authority.
                // For each active delegation, union the delegator's role permissions (as held
                // in that delegation's workspace) into the delegate's effective permissions.
                // This is recomputed on every request, so the elevation disappears automatically
                // the moment the delegation expires or is revoked — nothing persistent to undo.
                for (Delegation delegation : activeDelegations) {
                    memberRepo.findByWorkspaceIdAndUserId(
                                    delegation.getWorkspaceId(), delegation.getFromUserId())
                            .ifPresent(delegatorMember -> {
                                List<String> delegatorCodes = delegatorMember.getRole().getPermissionCodes();
                                for (String code : delegatorCodes) {
                                    if (!effectivePermissions.contains(code)) {
                                        effectivePermissions.add(code);
                                    }
                                }
                                log.debug("User {} is acting under delegation from {} in workspace {} — "
                                                + "injected {} delegator permission(s)",
                                        userId, delegation.getFromUserId(), delegation.getWorkspaceId(),
                                        delegatorCodes.size());
                            });
                }
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
