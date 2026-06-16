package et.com.cog.esms.core.admin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "system_setting")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class SystemSetting {
    @Id
    @Column(name = "key", nullable = false)
    private String key;

    @Column(nullable = false)
    private String value;

    private String description;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
