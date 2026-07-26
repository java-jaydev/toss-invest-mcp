package dev.jaydev.tossmcp.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * http 프로파일에서 Prometheus 스크레이프 엔드포인트가 실제로 뜨고, 우리가 등록한
 * 커스텀 지표(marketdata.*)를 노출하는지 검증한다. 설정만 확인하는 게 아니라
 * 컨텍스트를 부팅해 HTTP 로 긁어본다. stdio 프로파일엔 웹서버가 없으므로 이 노출은
 * http 에만 존재한다.
 *
 * <p>{@code @AutoConfigureObservability} 는 @SpringBootTest 가 테스트에서 기본으로
 * 끄는 지표 export 를 되살린다(실제 앱에서는 기본 활성이라 필요 없다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("http")
class PrometheusEndpointIT {

    @Test
    void prometheusEndpointExposesCustomMarketDataMeters(@Autowired TestRestTemplate rest) {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .as("커스텀 캐시 오프로드 지표가 스크레이프 출력에 있어야 한다")
                .contains("marketdata_upstream_calls")
                .contains("marketdata_l2_hits");
    }
}
