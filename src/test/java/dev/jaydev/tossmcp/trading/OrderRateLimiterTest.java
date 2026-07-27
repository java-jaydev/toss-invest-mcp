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
    void windowRetainsCallsRecordedLessThanASecondApart() {
        // 개장 혼잡 구간(한도 3)에서 300ms 간격으로 서로 다른 시각에 기록한다.
        // 컷오프가 "now - 1초"가 아니라 "now" 자체로 잘못 구현되면(=매 호출마다 이전 기록을
        // 전부 창 밖으로 밀어냄), 아래 3건이 모두 살아있다는 사실을 감지하지 못하고
        // 4번째 호출을 통과시켜 버린다.
        MovableClock clock = new MovableClock("2026-07-27T00:05:00.000Z");
        OrderRateLimiter limiter = new OrderRateLimiter(clock);

        assertThat(limiter.tryAcquire()).as("1번째 호출").isTrue();
        clock.moveTo("2026-07-27T00:05:00.300Z");
        assertThat(limiter.tryAcquire()).as("2번째 호출").isTrue();
        clock.moveTo("2026-07-27T00:05:00.600Z");
        assertThat(limiter.tryAcquire()).as("3번째 호출").isTrue();

        clock.moveTo("2026-07-27T00:05:00.900Z");
        // 가장 오래된 기록(00:05:00.000)도 900ms 전이라 아직 1초 창 안에 살아있으므로
        // 한도 3건이 이미 채워진 상태 — 4번째 호출은 거절돼야 한다.
        assertThat(limiter.tryAcquire()).as("4번째 호출은 한도 초과").isFalse();
    }

    @Test
    void rushWindowEndsAtNineTen() {
        // 2026-07-27T00:10Z 는 한국시간 09:10 — 혼잡 구간의 끝(포함되지 않음)
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:10:00Z"));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
    }

    @Test
    void rushWindowStartsAtNineSharp() {
        // 2026-07-27T00:00Z 는 한국시간 09:00:00 — 혼잡 구간의 시작(포함)
        OrderRateLimiter limiter = new OrderRateLimiter(new MovableClock("2026-07-27T00:00:00Z"));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire()).as("%d번째 호출", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).as("4번째 호출은 한도 초과").isFalse();
    }
}
