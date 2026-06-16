package et.com.cog.esms.core.contact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Parses Excel (.xlsx/.xls) files and imports contacts with dynamic fields.
 *
 * The first row is treated as headers — these become the dynamic field names.
 * Only "name" (or "Name") and "phone" / "phone_number" / "phoneE164" are required columns.
 * All other columns are stored in the contact's {@code extra} JSONB map.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelUploadService {

    private final ContactRepository contactRepo;
    private final ContactUploadRepository uploadRepo;
    private final ContactGroupMemberRepository memberRepo;

    /**
     * Parse an Excel file and create contacts, optionally adding them to a group.
     *
     * @return the ContactUpload tracking record
     */
    @Transactional
    public ContactUpload parseAndImport(UUID workspaceId, UUID uploadedBy,
                                         MultipartFile file, UUID groupId) {
        // Create upload tracking record
        ContactUpload upload = ContactUpload.builder()
                .workspaceId(workspaceId)
                .originalName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .groupId(groupId)
                .status("DRAFT")
                .uploadedBy(uploadedBy)
                .build();
        upload = uploadRepo.save(upload);

        try (InputStream is = file.getInputStream();
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                upload.setStatus("FAILED");
                upload.setErrors(List.of(Map.of("error", "File must have a header row and at least one data row")));
                upload.setCompletedAt(Instant.now());
                return uploadRepo.save(upload);
            }

            // Parse header row
            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellStringValue(cell).trim());
            }
            upload.setDetectedCols(headers);

            // Identify required column indices
            int nameIdx = findColumnIndex(headers, "name", "Name", "NAME", "Full Name", "full_name");
            int phoneIdx = findColumnIndex(headers, "phone", "Phone", "PHONE", "phone_number",
                    "Phone Number", "phoneE164", "phone_e164", "Mobile", "mobile", "MOBILE");

            if (nameIdx == -1 || phoneIdx == -1) {
                upload.setStatus("FAILED");
                upload.setErrors(List.of(Map.of("error",
                        "Required columns 'name' and 'phone' not found. Detected columns: " + headers)));
                upload.setCompletedAt(Instant.now());
                return uploadRepo.save(upload);
            }

            // Build column mapping
            Map<String, String> mapping = new LinkedHashMap<>();
            mapping.put(headers.get(nameIdx), "name");
            mapping.put(headers.get(phoneIdx), "phone_e164");
            for (int i = 0; i < headers.size(); i++) {
                if (i != nameIdx && i != phoneIdx) {
                    mapping.put(headers.get(i), "extra:" + headers.get(i));
                }
            }
            upload.setMapping(mapping);

            // Parse data rows
            int totalRows = 0;
            int importedCount = 0;
            int duplicateCount = 0;
            int errorCount = 0;
            List<Map<String, Object>> errors = new ArrayList<>();

            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;

                totalRows++;
                try {
                    String name = getCellStringValue(row.getCell(nameIdx)).trim();
                    String phone = normalizePhone(getCellStringValue(row.getCell(phoneIdx)).trim());

                    if (name.isEmpty() || phone.isEmpty()) {
                        errorCount++;
                        errors.add(Map.of("row", rowIdx + 1, "error", "Name and phone are required"));
                        continue;
                    }

                    // Check for duplicates within the workspace
                    if (contactRepo.existsByWorkspaceIdAndPhoneE164(workspaceId, phone)) {
                        duplicateCount++;
                        // Still add to group if exists
                        if (groupId != null) {
                            contactRepo.findByWorkspaceIdAndPhoneE164(workspaceId, phone)
                                    .ifPresent(existing -> {
                                        if (!memberRepo.existsByGroupIdAndContactId(groupId, existing.getId())) {
                                            memberRepo.save(ContactGroupMember.builder()
                                                    .groupId(groupId)
                                                    .contactId(existing.getId())
                                                    .build());
                                        }
                                    });
                        }
                        continue;
                    }

                    // Build extra fields map
                    Map<String, String> extra = new LinkedHashMap<>();
                    for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
                        if (colIdx != nameIdx && colIdx != phoneIdx) {
                            String value = getCellStringValue(row.getCell(colIdx)).trim();
                            if (!value.isEmpty()) {
                                extra.put(headers.get(colIdx), value);
                            }
                        }
                    }

                    Contact contact = Contact.builder()
                            .workspaceId(workspaceId)
                            .name(name)
                            .phoneE164(phone)
                            .extra(extra)
                            .uploadId(upload.getId())
                            .optOut(false)
                            .status("ACTIVE")
                            .build();
                    contact = contactRepo.save(contact);

                    // Add to group if specified
                    if (groupId != null) {
                        memberRepo.save(ContactGroupMember.builder()
                                .groupId(groupId)
                                .contactId(contact.getId())
                                .build());
                    }

                    importedCount++;
                } catch (Exception e) {
                    errorCount++;
                    errors.add(Map.of("row", rowIdx + 1, "error", e.getMessage()));
                }
            }

            upload.setRowCount(totalRows);
            upload.setImportedCount(importedCount);
            upload.setDuplicateCount(duplicateCount);
            upload.setErrorCount(errorCount);
            upload.setErrors(errors.isEmpty() ? null : errors);
            upload.setStatus("COMMITTED");
            upload.setCompletedAt(Instant.now());

            return uploadRepo.save(upload);

        } catch (Exception e) {
            log.error("Excel upload failed", e);
            upload.setStatus("FAILED");
            upload.setErrors(List.of(Map.of("error", "Failed to parse file: " + e.getMessage())));
            upload.setCompletedAt(Instant.now());
            return uploadRepo.save(upload);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────

    private int findColumnIndex(List<String> headers, String... candidates) {
        for (int i = 0; i < headers.size(); i++) {
            for (String candidate : candidates) {
                if (headers.get(i).equalsIgnoreCase(candidate)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                // Avoid scientific notation for phone numbers
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getStringCellValue();
            default -> "";
        };
    }

    private String normalizePhone(String phone) {
        // Remove spaces, dashes, etc.
        phone = phone.replaceAll("[\\s\\-().]", "");
        // Add Ethiopian country code if it starts with 09 or 07
        if (phone.startsWith("09") || phone.startsWith("07")) {
            phone = "+251" + phone.substring(1);
        }
        // Add + if missing
        if (phone.startsWith("251") && !phone.startsWith("+")) {
            phone = "+" + phone;
        }
        return phone;
    }
}
