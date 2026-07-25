package et.com.cog.esms.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import et.com.cog.esms.common.enums.MessageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusEvent {

    @JsonProperty("message_id")
    private UUID messageId;

    @JsonProperty("workspace_id")
    private UUID workspaceId;

    private MessageStatus status;

    private String carrier;

    @JsonProperty("carrier_msg_id")
    private String carrierMsgId;

    /**
     * Every SMSC message id this send produced — one per segment, so a
     * multi-part message has several. Carried on the SENT event only, so the
     * core can record a lookup row for each: an SMSC sends one delivery
     * receipt PER SEGMENT, and each quotes only its own id. Without all of
     * them, receipts for every segment but the primary would fail to match.
     */
    @JsonProperty("carrier_msg_ids")
    private List<String> carrierMsgIds;

    @JsonProperty("error_code")
    private String errorCode;

    private Instant timestamp;
}
