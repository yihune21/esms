package et.com.cog.esms.core.identity;

import et.com.cog.esms.core.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;

/**
 * Authentication controller — login, OTP verification, token refresh, logout.
 * Reference: LLD §6.1
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final OtpService otpService;
    private final SessionService sessionService;
    private final LockoutService lockoutService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final WorkspaceMemberRepository memberRepository;

    // ── POST /auth/login ─────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                   HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);

        // Check IP block
        if (lockoutService.isIpBlocked(ip)) {
            return problem(429, "Too many requests from this IP");
        }

        // Check account lockout
        if (lockoutService.isLocked(req.getUsername())) {
            return problem(423, "Account locked");
        }

        // Authenticate against local DB (AD integration can be added here)
        var userOpt = userRepository.findByUsername(req.getUsername());
        if (userOpt.isEmpty()) {
            lockoutService.recordFailure(req.getUsername(), ip);
            return problem(401, "Invalid credentials");
        }

        var user = userOpt.get();
        if (!"ACTIVE".equals(user.getStatus())) {
            return problem(423, "Account disabled");
        }

        if (user.getPasswordHash() == null ||
            !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            boolean locked = lockoutService.recordFailure(req.getUsername(), ip);
            return locked ? problem(423, "Account locked") : problem(401, "Invalid credentials");
        }

        // Generate OTP
        String otp = otpService.generateAndStore(user.getId().toString());
        log.info("OTP generated for user {} (dev mode, OTP={})", user.getUsername(), otp);

        // In production: send OTP via SMS pipeline
        // orchestrator.sendOtpSms(user.getMobileDecrypted(), otp);

        // Issue pre-auth token
        String preAuthToken = tokenProvider.createPreAuthToken(user.getId(), user.getUsername());
        lockoutService.clearFailures(req.getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preAuthToken", preAuthToken);
        body.put("otpSentTo", maskPhone(user.getMobileHash()));
        body.put("expiresIn", 180);

        return ResponseEntity.ok(body);
    }

    // ── POST /auth/verify-otp ────────────────────────────────────

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpRequest req,
                                       HttpServletResponse response) {
        Claims claims = tokenProvider.parseToken(req.getPreAuthToken());
        if (claims == null || !"pre_auth".equals(tokenProvider.getTokenType(claims))) {
            return problem(401, "Invalid or expired pre-auth token");
        }

        UUID userId = tokenProvider.getUserId(claims);
        String username = tokenProvider.getUsername(claims);

        var result = otpService.verify(userId.toString(), req.getOtp());
        return switch (result) {
            case VALID -> {
                // Fetch user workspaces
                var memberships = memberRepository.findByUserId(userId);
                List<Map<String, Object>> workspaces = memberships.stream()
                        .map(m -> {
                            Map<String, Object> ws = new LinkedHashMap<>();
                            ws.put("id", m.getWorkspace().getId());
                            ws.put("code", m.getWorkspace().getCode());
                            ws.put("role", m.getRole().getCode());
                            return ws;
                        }).toList();

                // Use first workspace by default (user selects later)
                UUID defaultWsId = memberships.isEmpty() ? null : memberships.get(0).getWorkspace().getId();
                String defaultRole = memberships.isEmpty() ? null : memberships.get(0).getRole().getCode();
                List<String> perms = memberships.isEmpty() ? List.of()
                        : memberships.get(0).getRole().getPermissionCodes();

                // Issue tokens
                String accessToken = tokenProvider.createAccessToken(userId, username,
                        defaultWsId, defaultRole, perms);
                String refreshToken = tokenProvider.createRefreshToken(userId);
                Claims refreshClaims = tokenProvider.parseToken(refreshToken);
                String refreshJti = tokenProvider.getJti(refreshClaims);

                // Create session
                sessionService.createSession(userId, refreshJti);

                // Set refresh token as HttpOnly cookie
                Cookie cookie = new Cookie("refreshToken", refreshToken);
                cookie.setHttpOnly(true);
                cookie.setSecure(true);
                cookie.setPath("/auth");
                cookie.setMaxAge((int) tokenProvider.getRefreshTokenTtl().toSeconds());
                response.addCookie(cookie);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("accessToken", accessToken);
                body.put("expiresIn", tokenProvider.getAccessTokenTtl().toSeconds());
                body.put("refreshCookieSet", true);
                body.put("workspaces", workspaces);

                yield ResponseEntity.ok(body);
            }
            case INVALID -> problem(401, "Invalid OTP");
            case EXPIRED -> problem(410, "OTP expired; restart login");
            case MAX_ATTEMPTS -> problem(410, "Maximum OTP attempts exceeded; restart login");
        };
    }

    // ── POST /auth/refresh ───────────────────────────────────────

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken == null) {
            return problem(401, "Refresh token missing");
        }

        Claims claims = tokenProvider.parseToken(refreshToken);
        if (claims == null || !"refresh".equals(tokenProvider.getTokenType(claims))) {
            return problem(401, "Refresh token invalid or revoked");
        }

        String refreshJti = tokenProvider.getJti(claims);
        if (!sessionService.isRefreshTokenValid(refreshJti)) {
            return problem(401, "Refresh token invalid or revoked");
        }

        UUID userId = tokenProvider.getUserId(claims);

        // Check idle timeout
        if (!sessionService.isSessionActive(userId)) {
            sessionService.revokeRefreshToken(refreshJti);
            return ResponseEntity.status(440)
                    .header("Content-Type", "application/problem+json")
                    .body(Map.of("type", "/errors/auth", "title", "Idle timeout", "status", 440));
        }

        // Fetch current workspace membership
        var memberships = memberRepository.findByUserId(userId);
        UUID wsId = memberships.isEmpty() ? null : memberships.get(0).getWorkspace().getId();
        String role = memberships.isEmpty() ? null : memberships.get(0).getRole().getCode();
        List<String> perms = memberships.isEmpty() ? List.of()
                : memberships.get(0).getRole().getPermissionCodes();
        String username = memberships.isEmpty() ? userId.toString()
                : userRepository.findById(userId).map(u -> u.getUsername()).orElse(userId.toString());

        String newAccessToken = tokenProvider.createAccessToken(userId, username, wsId, role, perms);
        sessionService.touchIdle(userId);

        return ResponseEntity.ok(Map.of(
                "accessToken", newAccessToken,
                "expiresIn", tokenProvider.getAccessTokenTtl().toSeconds()
        ));
    }

    // ── POST /auth/logout ────────────────────────────────────────

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);
        if (refreshToken != null) {
            Claims claims = tokenProvider.parseToken(refreshToken);
            if (claims != null) {
                sessionService.revokeRefreshToken(tokenProvider.getJti(claims));
            }
        }

        // Also deny the current access token if present
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            Claims accessClaims = tokenProvider.parseToken(authHeader.substring(7));
            if (accessClaims != null) {
                sessionService.denyAccessToken(tokenProvider.getJti(accessClaims),
                        tokenProvider.getAccessTokenTtl());
            }
        }

        // Clear cookie
        Cookie cookie = new Cookie("refreshToken", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    // ── POST /auth/resend-otp ────────────────────────────────────

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@Valid @RequestBody ResendOtpRequest req) {
        Claims claims = tokenProvider.parseToken(req.getPreAuthToken());
        if (claims == null || !"pre_auth".equals(tokenProvider.getTokenType(claims))) {
            return problem(401, "Invalid or expired pre-auth token");
        }

        UUID userId = tokenProvider.getUserId(claims);
        if (!otpService.canResend(userId.toString())) {
            return problem(429, "Please wait before requesting another OTP");
        }

        String otp = otpService.generateAndStore(userId.toString());
        otpService.markResendCooldown(userId.toString());
        log.info("OTP resent for user {} (dev mode, OTP={})", userId, otp);

        return ResponseEntity.ok(Map.of("message", "OTP resent", "expiresIn", 180));
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("refreshToken".equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }

    private String maskPhone(String hash) {
        return hash != null && hash.length() > 4 ? "****" + hash.substring(hash.length() - 4) : "****";
    }

    private ResponseEntity<Map<String, Object>> problem(int status, String title) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/problem+json")
                .body(Map.of("type", "/errors/auth", "title", title, "status", status));
    }

    // ── Request DTOs ─────────────────────────────────────────────

    @Data
    public static class LoginRequest {
        @NotBlank private String username;
        @NotBlank private String password;
    }

    @Data
    public static class OtpRequest {
        @NotBlank private String preAuthToken;
        @NotBlank private String otp;
    }

    @Data
    public static class ResendOtpRequest {
        @NotBlank private String preAuthToken;
    }
}
