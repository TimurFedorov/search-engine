package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
public class ConnectionData {

    @Value("${connection-data.user-agent}")
    private String userAgent;

    @Value("${connection-data.refferer}")
    private String referrer;

}