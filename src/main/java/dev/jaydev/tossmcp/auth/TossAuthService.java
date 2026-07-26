package dev.jaydev.tossmcp.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

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

    // synchronized 대신 ReentrantLock 을 쓴다. JDK 21 에서 synchronized 안에서
    // 블로킹(여기선 토큰 발급 HTTP)하면 캐리어 스레드가 핀되지만, ReentrantLock 은
    // LockSupport.park 기반이라 홀더도 대기자도 캐리어를 붙잡지 않는다.
    private final ReentrantLock refreshLock = new ReentrantLock();

    public TossAuthService(TossProperties props) {
        this.props = props;
        this.restClient = RestClient.create(props.baseUrl());
    }

    public String accessToken() {
        // 빠른 경로: 유효한 토큰이 있으면 락 없이 반환한다(캐시 히트는 직렬화하지 않는다).
        String cached = cachedToken;
        if (cached != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) {
            return cached;
        }
        refreshLock.lock();
        try {
            // 락을 기다리는 동안 다른 스레드가 이미 갱신했을 수 있으므로 재확인한다.
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
        } finally {
            refreshLock.unlock();
        }
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }
}
