package dev.jaydev.tossmcp.trading;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 하루에 보낸 주문 수를 센다. 한국 시장을 기준으로 하므로 한국시간 자정에 리셋한다.
 *
 * synchronized 대신 ReentrantLock 을 쓰는 이유는 이 저장소가 가상스레드 피닝 0 을
 * 테스트로 강제하기 때문이다(VirtualThreadPinningTest 참고).
 */
@Component
public class DailyOrderCounter {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private LocalDate day;
    private int count;

    public DailyOrderCounter(Clock clock) {
        this.clock = clock;
        this.day = today();
    }

    public int current() {
        lock.lock();
        try {
            rollOverIfNewDay();
            return count;
        } finally {
            lock.unlock();
        }
    }

    public void increment() {
        lock.lock();
        try {
            rollOverIfNewDay();
            count++;
        } finally {
            lock.unlock();
        }
    }

    private void rollOverIfNewDay() {
        LocalDate now = today();
        if (!now.equals(day)) {
            day = now;
            count = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KOREA);
    }
}
