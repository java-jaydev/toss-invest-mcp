package dev.jaydev.tossmcp.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRateLimiterTest {

    /** 테스트에서 "지금"을 임의로 옮기기 위한 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(String iso) {
            this.now = Instant.parse(iso);
        }

        void moveTo(String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsSixPerSecondOutsideTheOpeningRush() {
        // 2026-07-27T05:00Z 는 한국시간 14:00 — 혼잡 구간이 아니다
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T05:00:00Z"));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).as("7번째 호출은 한도 초과").isFalse();
    }

    @Test
    void allowsOnlyThreePerSecondDuringTheOpeningRush() {
        // 2026-07-27T00:05Z 는 한국시간 09:05 — 개장 직후 혼잡 구간
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:05:00Z"));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).as("4번째 호출은 한도 초과").isFalse();
    }

    @Test
    void windowSlidesAfterOneSecond() {
        MovableClock clock = new MovableClock("2026-07-27T05:00:00Z");
        OrderRateLimiter limiter = new OrderRateLimiter(clock);
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        clock.moveTo("2026-07-27T05:00:01.500Z");

        assertThat(limiter.tryAcquire()).as("1초가 지나면 다시 통과").isTrue();
    }

    @Test
    void rushWindowEndsAtNineTen() {
        // 2026-07-27T00:10Z 는 한국시간 09:10 — 혼잡 구간의 끝(포함되지 않음)
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:10:00Z"));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
    }
}
