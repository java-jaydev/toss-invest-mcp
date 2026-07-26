package dev.jaydev.tossmcp.loadtest;

import dev.jaydev.tossmcp.service.MarketDataService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * loadtest 프로파일 전용 HTTP shim. k6·부하 IT 가 MCP JSON-RPC 핸드셰이크 없이
 * 서비스·캐시·가상스레드 경로를 그대로 두들길 수 있게 한다. 프로덕션엔 존재하지 않는다.
 *
 * <p>측정 대상은 캐시 오프로드·요청병합(single-flight)·가상스레드 처리량이며,
 * MCP 프레이밍 오버헤드는 측정 범위가 아니다(문서에 명시).
 */
@RestController
@Profile("loadtest")
public class LoadTestController {

    private final MarketDataService marketData;

    public LoadTestController(MarketDataService marketData) {
        this.marketData = marketData;
    }

    /** 짧은 TTL(2s) 시세 — 지속 부하에서 주기적 재적재가 섞인 현실적 시나리오. */
    @GetMapping("/loadtest/prices")
    public String prices(@RequestParam String symbol) {
        return marketData.prices(symbol);
    }

    /** 긴 TTL(6h) 종목정보 — 부하 동안 재적재 없이 순수 캐시 오프로드/병합을 본다. */
    @GetMapping("/loadtest/stocks")
    public String stocks(@RequestParam String symbol) {
        return marketData.stocks(symbol);
    }
}
