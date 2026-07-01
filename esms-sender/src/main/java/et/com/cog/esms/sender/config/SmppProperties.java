package et.com.cog.esms.sender.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;


@Data
@ConfigurationProperties(prefix = "app.gateway.nib-smsc")
public class SmppProperties {

    private String host = "10.204.181.70";

    private int port = 5019;

    private String systemId = "6039";

    private String password;

    private String systemType = "";

    private String addressRange = "";

    private byte sourceAddressTon = 5;

    
    private byte sourceAddressNpi = 0;

  
    private byte destAddressTon = 1;


    private byte destAddressNpi = 1;


    private int enquireLinkTimer = 30;

    private long bindTimeoutMs = 10_000;

    private long reconnectDelayMs = 30_000;

    private int maxReconnectAttempts = 0;


    private String defaultSenderId = "NIB";
}
