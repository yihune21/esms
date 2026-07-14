package et.com.cog.esms.core.contact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelUploadService {

    private final ContactRepository contactRepo;
    private final ContactUploadRepository uploadRepo;
    private final ContactRowSaver rowSaver;


    public ContactUpload parseAndImport(UUID workspaceId, UUID uploadedBy,
                                         MultipartFile file, UUID groupId) {
        
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        if (contentType.length() > 255) {
            contentType = contentType.substring(0, 255);
        }
        ContactUpload upload = ContactUpload.builder()
                .workspaceId(workspaceId)
                .originalName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .contentType(contentType)
                .groupId(groupId)
                .status("DRAFT")
                .uploadedBy(uploadedBy)
                .build();
        upload = uploadRepo.save(upload);

        try (InputStream is = file.getInputStream();
             Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                upload.setStatus("FAILED");
                upload.setErrors(List.of(Map.of("error", "File must have a header row and at least one data row")));
                upload.setCompletedAt(Instant.now());
                return uploadRepo.save(upload);
            }

            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(getCellStringValue(cell).trim());
            }
            upload.setDetectedCols(headers);

            int nameIdx = findColumnIndex(headers, "name", "Name", "NAME", "Full Name", "full_name");
            int phoneIdx = findColumnIndex(headers, "phone", "Phone", "PHONE", "phone_number",
                    "Phone Number", "phoneE164", "phone_e164", "Mobile", "mobile", "MOBILE");
            
            
            if (phoneIdx == -1) {
                upload.setStatus("FAILED");
                upload.setErrors(List.of(Map.of("error",
                        "Required columns 'phone' not found. Detected columns: " + headers)));
                upload.setCompletedAt(Instant.now());
                return uploadRepo.save(upload);
            }

            Map<String, String> mapping = new LinkedHashMap<>();
            if (nameIdx != -1) {
                mapping.put(headers.get(nameIdx), "name");
            }
            mapping.put(headers.get(phoneIdx), "phone_e164");
            for (int i = 0; i < headers.size(); i++) {
                if (i != nameIdx && i != phoneIdx) {
                    mapping.put(headers.get(i), "extra:" + headers.get(i));
                }
            }
            upload.setMapping(mapping);

            int totalRows = 0;
            int importedCount = 0;
            int duplicateCount = 0;
            int errorCount = 0;
            List<Map<String, Object>> errors = new ArrayList<>();
            Set<String> seenPhonesInBatch = new LinkedHashSet<>();

            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;

                totalRows++;
                try {
                    String name = getCellStringValue(row.getCell(nameIdx)).trim();
                    String phone = normalizePhone(getCellStringValue(row.getCell(phoneIdx)).trim());

                    if (phone.isEmpty()) {
                        errorCount++;
                        errors.add(Map.of("row", rowIdx + 1, "error", "phone is required"));
                        continue;
                    }

                    if (name.length() > 255) {
                        errorCount++;
                        errors.add(Map.of("row", rowIdx + 1, "error", "Name exceeds maximum length (255 characters)"));
                        continue;
                    }

                    if (phone.length() > 20) {
                        errorCount++;
                        errors.add(Map.of("row", rowIdx + 1, "error", "Phone number exceeds maximum length"));
                        continue;
                    }

                    if (seenPhonesInBatch.contains(phone)) {
                        duplicateCount++;
                        continue;
                    }
                    seenPhonesInBatch.add(phone);

                    Map<String, String> extra = new LinkedHashMap<>();
                    for (int colIdx = 0; colIdx < headers.size(); colIdx++) {
                        if (colIdx != nameIdx && colIdx != phoneIdx) {
                            String value = getCellStringValue(row.getCell(colIdx)).trim();
                            if (!value.isEmpty()) {
                                extra.put(headers.get(colIdx), value);
                            }
                        }
                    }

                    Optional<Contact> existingContact = contactRepo.findByWorkspaceIdAndPhoneE164(workspaceId, phone);

                    if (existingContact.isPresent()) {
                        duplicateCount++;
                        try {
                            rowSaver.refreshContactRow(existingContact.get().getId(), name, extra, upload.getId(), groupId);
                        } catch (Exception e) {
                            errorCount++;
                            errors.add(Map.of("row", rowIdx + 1,
                                    "error", "Failed to refresh existing contact: " + e.getMessage()));
                            log.warn("Row {} failed to refresh duplicate contact: {}", rowIdx + 1, e.getMessage());
                        }
                        continue;
                    }

                    rowSaver.saveContactRow(workspaceId, name, phone, extra, upload.getId(), groupId);
                    importedCount++;

                } catch (Exception e) {
                    errorCount++;
                    errors.add(Map.of("row", rowIdx + 1, "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                    log.warn("Row {} failed to import: {}", rowIdx + 1, e.getMessage());
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
        phone = phone.replaceAll("[\\s\\-().]", "");
        if (phone.startsWith("09") || phone.startsWith("07")) {
            phone = "+251" + phone.substring(1);
        }
        if (phone.startsWith("251") && !phone.startsWith("+")) {
            phone = "+" + phone;
        }
        return phone;
    }
}