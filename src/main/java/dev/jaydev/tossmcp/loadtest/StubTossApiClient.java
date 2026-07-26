package dev.jaydev.tossmcp.loadtest;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.config.TossProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * loadtest 프로파일 전용 스텁 upstream. 실제 토스 API 를 부하로 두들길 수 없으므로,
 * 고정 지연 뒤 캔드(canned) JSON 을 돌려준다.
 *
 * <p><b>정직성:</b> 부하테스트가 재는 것은 "이 서버의 캐시·요청병합·가상스레드 처리량"이며
 * 토스의 실제 응답 지연이 아니다. 공개 수치에는 반드시 "캐시 + 고정지연 스텁 업스트림" 을
 * 캡션한다. 절대 지연/처리량을 실제 서비스 성능처럼 제시하지 않는다.
 */
@Component
@Profile("loadtest")
@Primary
public class StubTossApiClient extends TossApiClient {

    private final long latencyMillis;

    public StubTossApiClient(TossProperties props, TossAuthService auth,
                             @Value("${loadtest.upstream.latency-ms:40}") long latencyMillis) {
        super(props, auth);
        this.latencyMillis = latencyMillis;
    }

    @Override
    public String getPrices(String symbols) {
        return canned("prices", symbols);
    }

    @Override
    public String getOrderbook(String symbol) {
        return canned("orderbook", symbol);
    }

    @Override
    public String getTrades(String symbol, Integer count) {
        return canned("trades", symbol + ":" + count);
    }

    @Override
    public String getCandles(String symbol, String interval, Integer count, String before, Boolean adjusted) {
        return canned("candles", symbol + ":" + interval);
    }

    @Override
    public String getStocks(String symbols) {
        return canned("stocks", symbols);
    }

    private String canned(String kind, String key) {
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis); // upstream I/O 지연을 흉내낸다(가상스레드에서 언마운트됨)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return "{\"stub\":\"" + kind + "\",\"key\":\"" + key + "\",\"latencyMs\":" + latencyMillis + "}";
    }
}
