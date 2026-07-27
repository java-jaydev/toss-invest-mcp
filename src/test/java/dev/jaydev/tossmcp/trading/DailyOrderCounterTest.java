package dev.jaydev.tossmcp.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DailyOrderCounterTest {

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
    void countsIncrementsWithinTheSameDay() {
        DailyOrderCounter counter = new DailyOrderCounter(new MovableClock("2026-07-27T01:00:00Z"));

        counter.increment();
        counter.increment();

        assertThat(counter.current()).isEqualTo(2);
    }

    @Test
    void resetsAtKoreanMidnight() {
        // 2026-07-27T14:59Z 는 한국시간 07-27 23:59
        MovableClock clock = new MovableClock("2026-07-27T14:59:00Z");
        DailyOrderCounter counter = new DailyOrderCounter(clock);
        counter.increment();
        counter.increment();
        assertThat(counter.current()).isEqualTo(2);

        // 2026-07-27T15:01Z 는 한국시간 07-28 00:01 — 날짜가 바뀌었으므로 리셋
        clock.moveTo("2026-07-27T15:01:00Z");

        assertThat(counter.current()).isZero();
    }

    @Test
    void doesNotResetAtUtcMidnight() {
        // 2026-07-27T23:00Z 는 한국시간 07-28 08:00
        MovableClock clock = new MovableClock("2026-07-27T23:00:00Z");
        DailyOrderCounter counter = new DailyOrderCounter(clock);
        counter.increment();

        // 2026-07-28T00:30Z 는 한국시간 07-28 09:30 — 같은 한국 날짜라 유지되어야 한다
        clock.moveTo("2026-07-28T00:30:00Z");

        assertThat(counter.current()).isEqualTo(1);
    }
}
