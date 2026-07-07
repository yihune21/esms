package et.com.cog.esms.core.contact;

import et.com.cog.esms.core.audit.AuditService;
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
    private final AuditService                auditService;

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

    @PostMapping
    @PreAuthorize("hasAuthority('CONTACT_CREATE')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateGroupRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (groupRepo.existsByWorkspaceIdAndName(wsId, req.getName())) {
            auditService.log(wsId, "GROUP", "WARN", "GROUP_CREATE_DUPLICATE_NAME", "ContactGroup", null);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("title", "A group with that name already exists in this workspace"));
        }

        ContactGroup group = ContactGroup.builder()
                .workspaceId(wsId)
                .name(req.getName())
                .description(req.getDescription())
                .build();
        
        ContactGroup saved = groupRepo.save(group);
        auditService.log(wsId, "GROUP", "INFO", "GROUP_CREATED", "ContactGroup", saved.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(groupRepo.save(group)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<GroupDto> get(@PathVariable UUID id) {
        return groupRepo.findById(id)
                .map(g -> ResponseEntity.ok(toDto(g)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @RequestBody Map<String, Object> updates) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return groupRepo.findById(id)
                .map(g -> {
                    if (updates.containsKey("name"))        g.setName((String) updates.get("name"));
                    if (updates.containsKey("description")) g.setDescription((String) updates.get("description"));
                    
                    ContactGroup saved = groupRepo.save(g);
                    
                    auditService.log(wsId, "GROUP", "INFO", "GROUP_UPDATED", "ContactGroup", saved.getId());
                    return ResponseEntity.ok(toDto(groupRepo.save(g)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CONTACT_DELETE')")
    @Transactional
    public ResponseEntity<GroupDto> deactivate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return groupRepo.findById(id).map(g -> {
            g.setStatus("INACTIVE");

            ContactGroup saved = groupRepo.save(g);
            auditService.log(wsId, "GROUP", "INFO", "GROUP_DEACTIVATED", "ContactGroup", saved.getId());

            return ResponseEntity.ok(toDto(groupRepo.save(g)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    @Transactional
    public ResponseEntity<GroupDto> activate(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        return groupRepo.findById(id).map(g -> {
            g.setStatus("ACTIVE"); 
            ContactGroup saved = groupRepo.save(g);
            auditService.log(wsId, "GROUP", "INFO", "GROUP_ACTIVATED", "ContactGroup", saved.getId());
            return ResponseEntity.ok(toDto(groupRepo.save(g)));
        }).orElse(ResponseEntity.notFound().build());
    }

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

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    public ResponseEntity<?> addMember(@PathVariable UUID id,
                                       @Valid @RequestBody AddMemberRequest req) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

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

        auditService.log(wsId, "CONTACT", "INFO", "GROUP_MEMBER_ADDED", "ContactGroup", id);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "Contact added to group"));
    }

    @DeleteMapping("/{id}/members/{contactId}")
    @PreAuthorize("hasAuthority('CONTACT_UPDATE')")
    @Transactional
    public ResponseEntity<Void> removeMember(@PathVariable UUID id,
                                             @PathVariable UUID contactId) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();

        if (!memberRepo.existsByGroupIdAndContactId(id, contactId)) {
            return ResponseEntity.notFound().build();
        }
        memberRepo.deleteByGroupIdAndContactId(id, contactId);

        auditService.log(wsId, "CONTACT", "INFO", "GROUP_MEMBER_REMOVED", "ContactGroup", id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/upload")
    @PreAuthorize("hasAuthority('CONTACT_UPLOAD')")
    public ResponseEntity<?> uploadContacts(@PathVariable UUID id,
                                            @RequestParam("file") MultipartFile file) {
        if (!groupRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        UUID wsId   = WorkspaceContext.currentWorkspaceId();
        UUID userId = WorkspaceContext.currentUserId();

        if (wsId == null || userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("title", "No workspace context — select a workspace before uploading contacts"));
        }

        ContactUpload upload = excelUploadService.parseAndImport(wsId, userId, file, id);

        if ("FAILED".equals(upload.getStatus())) {
            auditService.log(wsId, "CONTACT", "WARN", "GROUP_UPLOAD_FAILED", "ContactUpload", upload.getId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(upload);
        }

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

        auditService.log(wsId, "CONTACT", "INFO", "GROUP_UPLOAD_COMPLETED", "ContactUpload", upload.getId());

        return ResponseEntity.ok(upload);
    }

    @PostMapping("/uploads")
    @PreAuthorize("hasAuthority('CONTACT_UPLOAD')")
    public ResponseEntity<?> standaloneUpload(@RequestParam("file") MultipartFile file) {
        UUID wsId   = WorkspaceContext.currentWorkspaceId();
        UUID userId = WorkspaceContext.currentUserId();

        if (wsId == null || userId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("title", "No workspace context — select a workspace before uploading contacts"));
        }

        ContactUpload upload = excelUploadService.parseAndImport(wsId, userId, file, null);

        if ("FAILED".equals(upload.getStatus())) {
            auditService.log(wsId, "CONTACT", "WARN", "STANDALONE_UPLOAD_FAILED", "ContactUpload", upload.getId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(upload);
        }

        auditService.log(wsId, "CONTACT", "INFO", "STANDALONE_UPLOAD_COMPLETED", "ContactUpload", upload.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(upload);
    }

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

    @GetMapping("/{id}/members/export")
    @PreAuthorize("hasAuthority('CONTACT_VIEW')")
    public ResponseEntity<byte[]> exportMembers(@PathVariable UUID id) {
        UUID wsId = WorkspaceContext.currentWorkspaceId();
        UUID userId = WorkspaceContext.currentUserId();
        if (!groupRepo.existsById(id)) {
            auditService.log(wsId, "CONTACT", "WARN", "GROUP_NOT_FOUND", "ContactGroup", userId);
            return ResponseEntity.notFound().build();
        }

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
            auditService.log(wsId, "CONTACT", "WARN", "GROUP_ROWS_EMPTY", "ContactGroup", userId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"group_members.csv\"")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body("id,name,phoneE164,branch,optOut,status\n".getBytes());
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter pw = new PrintWriter(baos)) {
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
        
        auditService.log(wsId, "CONTACT", "WARN", "GROUP_EXPORTED", "ContactGroup", userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"group_" + id + "_members.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }


    private GroupDto toDto(ContactGroup g) {
        long memberCount  = memberRepo.countByGroupId(g.getId());
        long uploadCount  = uploadRepo.countByGroupId(g.getId());

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


    @Data @AllArgsConstructor
    public static class GroupDto {
        private UUID        id;
        private UUID        workspaceId;
        private String      name;
        private String      description;
        private List<String> fields;
        private String      status;
        private Instant     createdAt;
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
