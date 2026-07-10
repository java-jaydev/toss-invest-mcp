package dev.jaydev.tossmcp.tools;

import dev.jaydev.tossmcp.client.TossApiClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 시세(Market Data) MCP 도구. 전부 공식 Open API 기반, read-only.
 */
@Component
public class MarketDataTools {

    private final TossApiClient api;

    public MarketDataTools(TossApiClient api) {
        this.api = api;
    }

    @Tool(description = "토스증권 종목의 현재가를 조회한다. symbol 은 종목 코드(예: 삼성전자 005930).")
    public String getPrice(@ToolParam(description = "종목 코드") String symbol) {
        return api.getPrice(symbol);
    }
}
