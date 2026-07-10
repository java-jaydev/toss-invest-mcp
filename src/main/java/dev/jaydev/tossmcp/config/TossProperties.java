package dev.jaydev.tossmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스 Open API 접속 설정. 시크릿은 코드에 넣지 않고 환경변수로만 주입한다.
 * (TOSS_CLIENT_ID / TOSS_CLIENT_SECRET / TOSS_ACCOUNT)
 */
@ConfigurationProperties(prefix = "toss")
public record TossProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String account
) {
}
