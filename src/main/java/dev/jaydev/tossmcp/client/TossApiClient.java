package dev.jaydev.tossmcp.client;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 토스 Open API 호출 래퍼. 모든 요청에 Bearer 토큰을 붙인다.
 * MVP 단계에서는 응답을 원본 JSON 문자열로 반환한다(LLM 이 직접 읽음).
 */
@Component
public class TossApiClient {

    private final RestClient http;
    private final TossAuthService auth;

    public TossApiClient(TossProperties props, TossAuthService auth) {
        this.http = RestClient.create(props.baseUrl());
        this.auth = auth;
    }

    /** GET /api/v1/prices — 현재가 */
    public String getPrice(String symbol) {
        return http.get()
                .uri(uri -> uri.path("/api/v1/prices").queryParam("symbol", symbol).build())
                .header("Authorization", "Bearer " + auth.accessToken())
                .retrieve()
                .body(String.class);
    }
}
