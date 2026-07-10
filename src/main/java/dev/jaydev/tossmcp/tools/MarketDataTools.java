package dev.jaydev.tossmcp.tools;

import dev.jaydev.tossmcp.client.TossApiClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 시세(Market Data) MCP 도구. 전부 공식 Open API 기반, read-only.
 * 파라미터는 공식 OpenAPI 스펙으로 검증했다.
 */
@Component
public class MarketDataTools {

    private final TossApiClient api;

    public MarketDataTools(TossApiClient api) {
        this.api = api;
    }

    @Tool(description = "토스증권 종목의 현재가를 조회한다. symbols 는 종목 코드이며 콤마로 여러 개(최대 200) 넣을 수 있다. 예: 삼성전자 005930, SK하이닉스 000660.")
    public String getPrices(@ToolParam(description = "종목 코드(콤마 구분, 최대 200개)") String symbols) {
        return api.getPrices(symbols);
    }

    @Tool(description = "토스증권 종목의 호가(매수·매도 잔량)를 조회한다. symbol 은 단일 종목 코드.")
    public String getOrderbook(@ToolParam(description = "종목 코드(단일)") String symbol) {
        return api.getOrderbook(symbol);
    }

    @Tool(description = "토스증권 종목의 최근 체결 내역을 조회한다. symbol 은 단일 종목 코드.")
    public String getTrades(
            @ToolParam(description = "종목 코드(단일)") String symbol,
            @ToolParam(description = "조회 건수(기본 50, 최대 50)", required = false) Integer count) {
        return api.getTrades(symbol, count);
    }

    @Tool(description = "토스증권 종목의 캔들(시가·고가·저가·종가) 차트를 조회한다.")
    public String getCandles(
            @ToolParam(description = "종목 코드(단일)") String symbol,
            @ToolParam(description = "간격: 1m(1분) 또는 1d(1일)") String interval,
            @ToolParam(description = "봉 개수(기본 100, 최대 200)", required = false) Integer count,
            @ToolParam(description = "페이지네이션 상한(ISO 8601 시각)", required = false) String before,
            @ToolParam(description = "수정주가 적용 여부(기본 true)", required = false) Boolean adjusted) {
        return api.getCandles(symbol, interval, count, before, adjusted);
    }

    @Tool(description = "토스증권 종목의 기본 정보(이름·시장·종류·상장일 등)를 조회한다. symbols 는 콤마로 여러 개(최대 200).")
    public String getStocks(@ToolParam(description = "종목 코드(콤마 구분, 최대 200개)") String symbols) {
        return api.getStocks(symbols);
    }
}
