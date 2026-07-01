package et.com.cog.esms.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import et.com.cog.esms.common.enums.Encoding;
import et.com.cog.esms.common.enums.Priority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendCommand {

    @JsonProperty("message_id")
    private UUID messageId;

    @JsonProperty("workspace_id")
    private UUID workspaceId;

    @JsonProperty("campaign_id")
    private String campaignId;

    @JsonProperty("sender_mask")
    private String senderMask;

    private String to;

    private String body;

    private Encoding encoding;

    private Priority priority;

    @JsonProperty("scheduled_at")
    private Instant scheduledAt;

    @JsonProperty("resolved_carrier")
    private String resolvedCarrier;

    @JsonProperty("callback_url")
    private String callbackUrl;

    @JsonProperty("issued_at")
    private Instant issuedAt;
}
