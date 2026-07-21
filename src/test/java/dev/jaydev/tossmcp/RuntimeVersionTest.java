package dev.jaydev.tossmcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가상스레드는 Java 21 이상에서만 동작한다. 툴체인이 조용히 내려가면
 * spring.threads.virtual.enabled 는 무시되고 성능 측정이 거짓말이 된다.
 * 이 테스트가 그 회귀를 막는다.
 */
class RuntimeVersionTest {

    @Test
    void runsOnJava21OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
