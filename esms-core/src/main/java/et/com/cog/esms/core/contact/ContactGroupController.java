package et.com.cog.esms.core.contact;

import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contact-group CRUD and membership management.
 * Groups are used as campaign recipient lists (recipient_group_id on campaign).
 * Reference: LLD §4.3
 */
@Slf4j
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class ContactGroupController {

    private final ContactGroupRepository groupRepo;
    private final ContactGroupMemberRepository memberRepo;
    private final ContactRepository contactRepo;

    // ── GET /groups ──────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<GroupDto>> list() {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return ResponseEntity.ok(
                groupRepo.findByWorkspaceIdOrderByCreatedAtDesc(wsId)
                        .stream().map(this::toDto).collect(Collectors.toList()));
    }

    // ── POST /groups ─────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateGroupRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (groupRepo.existsByWorkspaceIdAndName(wsId, req.getName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "A group with that name already exists in this workspace"));
        }

        ContactGroup group = ContactGroup.builder()
                .workspaceId(wsId)
                .name(req.getName())
                .description(req.getDescription())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(groupRepo.save(group)));
    }

    // ── GET /groups/{id} ─────────────────────────────────────────
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<GroupDto> get(@PathVariable UUID id) {
        return groupRepo.findById(id)
                .map(g -> ResponseEntity.ok(toDto(g)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /groups/{id} ───────────────────────────────────────
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        return groupRepo.findById(id)
                .map(g -> {
                    if (updates.containsKey("name"))        g.setName((String) updates.get("name"));
                    if (updates.containsKey("description")) g.setDescription((String) updates.get("description"));
                    return ResponseEntity.ok(toDto(groupRepo.save(g)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE /groups/{id} ──────────────────────────────────────
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!groupRepo.existsById(id)) return ResponseEntity.notFound().build();
        groupRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── GET /groups/{id}/members ─────────────────────────────────
    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<Map<String, Object>>> members(@PathVariable UUID id) {
        if (!groupRepo.existsById(id)) return ResponseEntity.notFound().build();

        List<Map<String, Object>> result = memberRepo.findByGroupId(id).stream()
                .map(m -> contactRepo.findById(m.getContactId())
                        .map(c -> {
                            Map<String, Object> map = new LinkedHashMap<>();
                            map.put("contactId", c.getId());
                            map.put("name", c.getName());
                            map.put("phoneE164", c.getPhoneE164());
                            map.put("branch", c.getBranch());
                            map.put("optOut", c.isOptOut());
                            return map;
                        }).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ── POST /groups/{id}/members ────────────────────────────────
    @PostMapping("/{id}/members")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> addMember(@PathVariable UUID id,
                                       @Valid @RequestBody AddMemberRequest req) {
        if (!groupRepo.existsById(id)) {
            return ResponseEntity.badRequest().body(Map.of("title", "Group not found"));
        }
        if (!contactRepo.existsById(req.getContactId())) {
            return ResponseEntity.badRequest().body(Map.of("title", "Contact not found"));
        }
        if (memberRepo.existsByGroupIdAndContactId(id, req.getContactId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "Contact is already a member of this group"));
        }

        memberRepo.save(ContactGroupMember.builder()
                .groupId(id)
                .contactId(req.getContactId())
                .build());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Contact added to group"));
    }

    // ── DELETE /groups/{id}/members/{contactId} ──────────────────
    @DeleteMapping("/{id}/members/{contactId}")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    @Transactional
    public ResponseEntity<Void> removeMember(@PathVariable UUID id,
                                             @PathVariable UUID contactId) {
        if (!memberRepo.existsByGroupIdAndContactId(id, contactId)) {
            return ResponseEntity.notFound().build();
        }
        memberRepo.deleteByGroupIdAndContactId(id, contactId);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private GroupDto toDto(ContactGroup g) {
        return new GroupDto(g.getId(), g.getWorkspaceId(), g.getName(),
                g.getDescription(), g.getCreatedAt());
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class GroupDto {
        private UUID id;
        private UUID workspaceId;
        private String name;
        private String description;
        private Instant createdAt;
    }

    @Data
    public static class CreateGroupRequest {
        @NotBlank private String name;
        private String description;
    }

    @Data
    public static class AddMemberRequest {
        private UUID contactId;
    }
}
