package dev.jaydev.tossmcp.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * OAuth2 Client Credentials 토큰을 발급·캐시하고, 만료 전 자동 갱신한다.
 * POST {base}/oauth2/token (grant_type=client_credentials)
 */
@Service
public class TossAuthService {

    private final RestClient restClient;
    private final TossProperties props;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public TossAuthService(TossProperties props) {
        this.props = props;
        this.restClient = RestClient.create(props.baseUrl());
    }

    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());

        TokenResponse resp = restClient.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (resp == null || resp.accessToken() == null) {
            throw new IllegalStateException("토스 토큰 발급 실패: 응답이 비어 있음");
        }
        long ttl = resp.expiresIn() != null ? resp.expiresIn() : 3600L;
        this.cachedToken = resp.accessToken();
        this.expiresAt = Instant.now().plusSeconds(ttl);
        return cachedToken;
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }
}
