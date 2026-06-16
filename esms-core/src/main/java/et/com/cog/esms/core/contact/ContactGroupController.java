package et.com.cog.esms.core.contact;

import et.com.cog.esms.core.identity.UserRepository;
import et.com.cog.esms.core.security.WorkspaceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contact-group CRUD + membership + upload history + export.
 * Reference: LLD §4.3
 */
@Slf4j
@RestController
@RequestMapping("/groups")
@RequiredArgsConstructor
public class ContactGroupController {

    private final ContactGroupRepository      groupRepo;
    private final ContactGroupMemberRepository memberRepo;
    private final ContactRepository           contactRepo;
    private final ContactUploadRepository     uploadRepo;
    private final ExcelUploadService          excelUploadService;
    private final UserRepository              userRepo;

    // ── GET /groups ──────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<GroupDto>> list(
            @RequestParam(required = false) String status) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        List<ContactGroup> groups = status != null
                ? groupRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, status)
                : groupRepo.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(wsId, "ACTIVE");
        return ResponseEntity.ok(groups.stream().map(this::toDto).collect(Collectors.toList()));
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

    // ── POST /groups/{id}/deactivate ─────────────────────────────
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    @Transactional
    public ResponseEntity<GroupDto> deactivate(@PathVariable UUID id) {
        return groupRepo.findById(id).map(g -> {
            g.setStatus("INACTIVE");
            return ResponseEntity.ok(toDto(groupRepo.save(g)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── POST /groups/{id}/activate ───────────────────────────────
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    @Transactional
    public ResponseEntity<GroupDto> activate(@PathVariable UUID id) {
        return groupRepo.findById(id).map(g -> {
            g.setStatus("ACTIVE");
            return ResponseEntity.ok(toDto(groupRepo.save(g)));
        }).orElse(ResponseEntity.notFound().build());
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
                            map.put("status", c.getStatus());
                            if (c.getExtra() != null && !c.getExtra().isEmpty()) {
                                map.putAll(c.getExtra());
                            }
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

    // ── POST /groups/{id}/upload ─────────────────────────────────
    @PostMapping("/{id}/upload")
    @PreAuthorize("hasAuthority('CONTACT_UPLOAD')")
    public ResponseEntity<?> uploadContacts(@PathVariable UUID id,
                                            @RequestParam("file") MultipartFile file) {
        if (!groupRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        UUID wsId   = WorkspaceContext.currentWorkspaceId();
        UUID userId = WorkspaceContext.currentUserId();

        ContactUpload upload = excelUploadService.parseAndImport(wsId, userId, file, id);

        if ("FAILED".equals(upload.getStatus())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(upload);
        }

        // Update group's known dynamic fields from this upload's headers
        if (upload.getMapping() != null && !upload.getMapping().isEmpty()) {
            groupRepo.findById(id).ifPresent(group -> {
                List<String> currentFields = new ArrayList<>(group.getFields() != null ? group.getFields() : new ArrayList<>());
                upload.getMapping().keySet().forEach(k -> {
                    if (!currentFields.contains(k)) currentFields.add(k);
                });
                group.setFields(currentFields);
                groupRepo.save(group);
            });
        }

        return ResponseEntity.ok(upload);
    }

    // ── POST /uploads  (standalone — no group required) ──────────
    /**
     * Standalone recipients upload. Parses the file without attaching it to a group.
     * Returns a ContactUpload record with an uploadId the campaign composer can reference.
     */
    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('CONTACT_UPLOAD')")
    public ResponseEntity<?> standaloneUpload(@RequestParam("file") MultipartFile file) {
        UUID wsId   = WorkspaceContext.currentWorkspaceId();
        UUID userId = WorkspaceContext.currentUserId();

        // groupId = null → contacts created but not attached to any named group
        ContactUpload upload = excelUploadService.parseAndImport(wsId, userId, file, null);

        if ("FAILED".equals(upload.getStatus())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(upload);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(upload);
    }

    // ── GET /groups/{id}/uploads  (upload history) ───────────────
    @GetMapping("/{id}/uploads")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<List<UploadHistoryDto>> uploadHistory(@PathVariable UUID id) {
        if (!groupRepo.existsById(id)) return ResponseEntity.notFound().build();

        List<UploadHistoryDto> history = uploadRepo.findByGroupIdOrderByCreatedAtDesc(id)
                .stream()
                .map(u -> {
                    String uploaderName = userRepo.findById(u.getUploadedBy())
                            .map(user -> user.getDisplayName()).orElse(null);
                    return new UploadHistoryDto(
                            u.getId(), u.getOriginalName(), u.getFileSize(),
                            u.getStatus(), u.getRowCount(), u.getImportedCount(),
                            u.getDuplicateCount(), u.getErrorCount(),
                            u.getUploadedBy(), uploaderName,
                            u.getCreatedAt(), u.getCompletedAt()
                    );
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(history);
    }

    // ── GET /groups/{id}/members/export  (CSV export) ────────────
    @GetMapping("/{id}/members/export")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<byte[]> exportMembers(@PathVariable UUID id) {
        if (!groupRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Collect all contacts in this group
        List<Map<String, Object>> rows = memberRepo.findByGroupId(id).stream()
                .map(m -> contactRepo.findById(m.getContactId()).orElse(null))
                .filter(Objects::nonNull)
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.getId().toString());
                    row.put("name", c.getName());
                    row.put("phoneE164", c.getPhoneE164());
                    row.put("branch", c.getBranch() != null ? c.getBranch() : "");
                    row.put("optOut", String.valueOf(c.isOptOut()));
                    row.put("status", c.getStatus());
                    if (c.getExtra() != null) row.putAll(c.getExtra());
                    return row;
                })
                .collect(Collectors.toList());

        if (rows.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"group_members.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body("id,name,phoneE164,branch,optOut,status\n".getBytes());
        }

        // Build CSV
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(baos)) {
            // Header row from the first record's key set
            Set<String> keys = rows.get(0).keySet();
            pw.println(String.join(",", keys));

            for (Map<String, Object> row : rows) {
                String line = keys.stream()
                        .map(k -> {
                            Object v = row.get(k);
                            String s = v != null ? v.toString().replace("\"", "\"\"") : "";
                            return "\"" + s + "\"";
                        })
                        .collect(Collectors.joining(","));
                pw.println(line);
            }
        }

        byte[] csv = baos.toByteArray();

        groupRepo.findById(id).ifPresent(g ->
                log.info("Exported {} members from group '{}' ({})", rows.size(), g.getName(), id));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"group_" + id + "_members.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private GroupDto toDto(ContactGroup g) {
        // Count members and latest upload metadata for this group
        long memberCount  = memberRepo.countByGroupId(g.getId());
        long uploadCount  = uploadRepo.countByGroupId(g.getId());

        // Latest upload info (for the GroupDto summary)
        ContactUpload latestUpload = uploadRepo.findByGroupIdOrderByCreatedAtDesc(g.getId())
                .stream().findFirst().orElse(null);

        String originalFileName  = latestUpload != null ? latestUpload.getOriginalName() : null;
        Integer recordCount      = latestUpload != null ? latestUpload.getImportedCount() : null;
        UUID uploadedBy          = latestUpload != null ? latestUpload.getUploadedBy() : null;
        String uploadedByName    = uploadedBy != null
                ? userRepo.findById(uploadedBy).map(u -> u.getDisplayName()).orElse(null) : null;
        Instant uploadDate       = latestUpload != null ? latestUpload.getCreatedAt() : null;

        return new GroupDto(
                g.getId(), g.getWorkspaceId(), g.getName(), g.getDescription(),
                g.getFields(), g.getStatus(), g.getCreatedAt(),
                (int) memberCount, originalFileName, recordCount,
                uploadedBy, uploadedByName, uploadDate, (int) uploadCount
        );
    }

    // ── DTOs ─────────────────────────────────────────────────────

    @Data @AllArgsConstructor
    public static class GroupDto {
        private UUID        id;
        private UUID        workspaceId;
        private String      name;
        private String      description;
        private List<String> fields;
        private String      status;
        private Instant     createdAt;
        // ── enriched fields ──
        private int         memberCount;
        private String      originalFileName;
        private Integer     recordCount;
        private UUID        uploadedBy;
        private String      uploadedByName;
        private Instant     uploadDate;
        private int         uploadCount;
    }

    @Data @AllArgsConstructor
    public static class UploadHistoryDto {
        private UUID    id;
        private String  originalFileName;
        private Long    fileSize;
        private String  status;
        private Integer rowCount;
        private Integer importedCount;
        private Integer duplicateCount;
        private Integer errorCount;
        private UUID    uploadedBy;
        private String  uploadedByName;
        private Instant createdAt;
        private Instant completedAt;
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
