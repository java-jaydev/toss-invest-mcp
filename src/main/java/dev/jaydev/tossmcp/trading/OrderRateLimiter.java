package dev.jaydev.tossmcp.trading;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 주문 API 전용 초당 요청 한도. 토스 규정은 초당 6건이며, 개장 직후 09:00~09:10 구간만
 * 초당 3건으로 줄어든다.
 *
 * 한도를 넘으면 기다리지 않고 즉시 거절한다. 도구 호출 안에서 잠들면 호출자가 영문도 모른 채
 * 묶이기 때문에, 곧바로 거절하고 다시 시도하게 하는 편이 낫다.
 */
@Component
public class OrderRateLimiter {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final LocalTime RUSH_START = LocalTime.of(9, 0);
    private static final LocalTime RUSH_END = LocalTime.of(9, 10);
    private static final int LIMIT_NORMAL = 6;
    private static final int LIMIT_RUSH = 3;

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Instant> recent = new ArrayDeque<>();

    public OrderRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 한도 안이면 호출을 기록하고 true, 초당 한도를 넘으면 false 를 돌려준다. */
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = clock.instant();
            Instant oneSecondAgo = now.minusSeconds(1);
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(oneSecondAgo)) {
                recent.pollFirst();
            }
            if (recent.size() >= limitAt(now)) {
                return false;
            }
            recent.addLast(now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 개장 직후 혼잡 구간에는 한도가 절반으로 줄어든다. */
    private int limitAt(Instant now) {
        LocalTime time = LocalTime.ofInstant(now, KOREA);
        boolean openingRush = !time.isBefore(RUSH_START) && time.isBefore(RUSH_END);
        return openingRush ? LIMIT_RUSH : LIMIT_NORMAL;
    }
}
