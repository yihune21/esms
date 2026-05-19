package et.com.cog.esms.core.identity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ad_sam", unique = true)
    private String adSam;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String email;

    @Column(name = "mobile_enc")
    private byte[] mobileEnc;

    @Column(name = "mobile_hash")
    private String mobileHash;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failed_logins")
    private short failedLogins;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
