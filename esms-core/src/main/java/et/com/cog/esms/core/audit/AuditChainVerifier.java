package et.com.cog.esms.core.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Walks the audit chain and reports whether it is intact.
 *
 * Without this the chain is decorative: hashes were being written but nothing
 * ever recomputed them, so tampering would never have been noticed. Two things
 * are checked for every row, in seq order:
 *
 *   - its row_hash still matches a fresh hash of its contents, which catches
 *     any edit to the row itself; and
 *   - its prev_hash equals the previous row's row_hash, which catches deleted,
 *     reordered or inserted rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditChainVerifier {

    private static final int PAGE_SIZE = 500;

    private final AuditLogRepository auditLogRepo;

    public record Broken(long seq, UUID id, String reason) {}

    public record Result(boolean intact, long rowsChecked, List<Broken> breaks) {}

    /**
     * @param maxRows safety bound so verifying a large log cannot exhaust
     *                memory; pass 0 or less to check everything
     */
    @Transactional(readOnly = true)
    public Result verify(long maxRows) {
        List<Broken> breaks = new ArrayList<>();
        String expectedPrev = null;
        long checked = 0;
        int page = 0;

        while (true) {
            List<AuditLog> rows = auditLogRepo.findAllByOrderBySeqAsc(PageRequest.of(page++, PAGE_SIZE));
            if (rows.isEmpty()) break;

            for (AuditLog row : rows) {
                // The first row legitimately has no predecessor.
                boolean linkOk = checked == 0
                        ? row.getPrevHash() == null || row.getPrevHash().equals(expectedPrev)
                        : java.util.Objects.equals(row.getPrevHash(), expectedPrev);
                if (!linkOk) {
                    breaks.add(new Broken(row.getSeq(), row.getId(),
                            "prev_hash does not match the preceding row - a row was deleted, "
                                    + "reordered or inserted here"));
                }

                String recomputed = AuditService.hash(row);
                if (!recomputed.equals(row.getRowHash())) {
                    breaks.add(new Broken(row.getSeq(), row.getId(),
                            "row_hash does not match the row contents - this entry was modified"));
                }

                expectedPrev = row.getRowHash();
                checked++;
                if (maxRows > 0 && checked >= maxRows) {
                    return finish(breaks, checked);
                }
            }
            if (rows.size() < PAGE_SIZE) break;
        }
        return finish(breaks, checked);
    }

    private Result finish(List<Broken> breaks, long checked) {
        if (!breaks.isEmpty()) {
            log.error("AUDIT CHAIN VERIFICATION FAILED - {} break(s) across {} rows", breaks.size(), checked);
        }
        return new Result(breaks.isEmpty(), checked, breaks);
    }
}
