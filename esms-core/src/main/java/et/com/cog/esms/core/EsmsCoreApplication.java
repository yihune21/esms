package et.com.cog.esms.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * eSMS-Core — the main application (modular monolith).
 * Handles all user-facing and stateful operations:
 * identity, workspaces, contacts, templates, campaigns,
 * approvals, scheduling, reporting, and audit.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class EsmsCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsmsCoreApplication.class, args);
    }
}
