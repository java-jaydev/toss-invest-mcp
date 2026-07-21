package dev.jaydev.tossmcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가상스레드가 "설정됐다"가 아니라 "실제로 요청을 처리한다"를 증명한다.
 * 설정값을 읽어 단언하는 것은 증거가 되지 못한다 — 요청을 처리하는
 * 스레드 자신에게 isVirtual() 을 묻는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
class VirtualThreadProbeIT {

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ThreadProbeController threadProbeController() {
            return new ThreadProbeController();
        }
    }

    @RestController
    static class ThreadProbeController {
        @GetMapping("/__test/thread")
        String describe() {
            Thread t = Thread.currentThread();
            return t.isVirtual() + ":" + t.getName();
        }
    }

    @Test
    void tomcatServesRequestsOnVirtualThreads(@org.springframework.beans.factory.annotation.Autowired
                                              TestRestTemplate rest) {
        String body = rest.getForObject("/__test/thread", String.class);

        assertThat(body).as("응답 처리 스레드가 가상스레드여야 한다. 실제 값: %s", body)
                .startsWith("true:");
    }
}
