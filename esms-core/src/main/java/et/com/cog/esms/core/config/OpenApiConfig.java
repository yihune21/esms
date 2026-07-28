package et.com.cog.esms.core.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI configuration.
 *
 * Exposes global JWT bearer security scheme so every protected endpoint
 * in Swagger UI has the padlock button and sends Authorization: Bearer <token>.
 *
 * UI  : http://localhost:8080/swagger-ui.html
 * Spec: http://localhost:8080/v3/api-docs
 *
 */
@Configuration
@OpenAPIDefinition(
    info = @Info(
        title       = "NIC eSMS Platform API",
        version     = "1.0.0",
        description = """
            Enterprise SMS Communication Platform for Nib Insurance Company (ESCP-2026).

            **Authentication flow**
            1. `POST /auth/login` with your Active Directory username and password
               → returns `accessToken` (+ HttpOnly refresh cookie). Single step: the
               platform is LAN-only, so there is no SMS OTP.
            2. Click **Authorize** above, paste the `accessToken` to enable all protected calls.

            Credentials are checked against the NIC domain controller. Accounts Active
            Directory does not hold (the seeded `superadmin`) fall back to a local password.

            **DLR / Webhook callbacks** are handled by the `esms-sender` service on port **8081**.
            """,
        license = @License(name = "Proprietary — Nib Insurance Company")
    ),
    servers = {
        @Server(url = "http://localhost:8080",     description = "Local Dev — eSMS Core"),
        @Server(url = "https://esms.nib.internal", description = "Production — eSMS Core")
    },
    security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
    name        = "bearerAuth",
    type        = SecuritySchemeType.HTTP,
    scheme      = "bearer",
    bearerFormat = "JWT",
    in          = SecuritySchemeIn.HEADER,
    description = "JWT access token. Obtain via POST /auth/login"
)
public class OpenApiConfig {
    // No beans required — annotations are processed by springdoc at startup.
}
