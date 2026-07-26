package dev.jaydev.tossmcp.loadtest;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부하 하네스가 실제로 캐시 오프로드·single-flight 를 보이는지 HTTP 로 검증한다.
 * 핵심 증거는 marketdata.upstream.calls 카운터의 델타 — 하드웨어 독립적이고 CI 에서
 * 결정론적으로 재현된다(k6/Grafana 없이도). 절대 지연/처리량이 아니라 "요청 대비
 * upstream 호출 수"라는 성질을 단언한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles({"http", "loadtest"})
class LoadOffloadIT {

    private static double upstreamCalls(MeterRegistry registry) {
        return registry.get("marketdata.upstream.calls").counter().count();
    }

    @Test
    void concurrentBurstOnOneKeyCollapsesToSingleUpstreamCall(
            @Autowired TestRestTemplate rest, @Autowired MeterRegistry registry) throws Exception {
        String url = "/loadtest/stocks?symbol=BURST"; // 6h TTL → 부하 동안 재적재 없음
        double before = upstreamCalls(registry);

        int n = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        rest.getForObject(url, String.class);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }

        // 진행 중 future 를 공유(병합)했든 이미 캐시된 값을 읽었든, upstream 은 정확히 1회.
        assertThat(upstreamCalls(registry) - before)
                .as("동일 키 %d 동시요청은 single-flight+캐시로 upstream 1회여야 한다", n)
                .isEqualTo(1.0);
    }

    @Test
    void repeatedRequestsHitCacheSoUpstreamCalledOnce(
            @Autowired TestRestTemplate rest, @Autowired MeterRegistry registry) {
        String url = "/loadtest/stocks?symbol=REPEAT";
        double before = upstreamCalls(registry);

        for (int i = 0; i < 500; i++) {
            rest.getForObject(url, String.class);
        }

        assertThat(upstreamCalls(registry) - before)
                .as("500 반복요청은 캐시로 upstream 1회여야 한다")
                .isEqualTo(1.0);
    }
}
