package dev.jaydev.tossmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 실매매 안전장치 설정. 기본값은 "꺼짐 + 조여짐" 이며 사용자가 의도적으로 열어야 한다.
 * enabled 가 false 면 어떤 요청도 실제로 전송되지 않는다.
 */
@ConfigurationProperties(prefix = "toss.trading")
public record TossTradingProperties(
        boolean enabled,
        BigDecimal maxOrderNotionalKrw,
        BigDecimal maxOrderNotionalUsd,
        int dailyOrderCount,
        List<String> symbolAllowlist
) {
    public TossTradingProperties {
        if (maxOrderNotionalKrw == null) {
            maxOrderNotionalKrw = new BigDecimal("100000");
        }
        if (maxOrderNotionalUsd == null) {
            maxOrderNotionalUsd = new BigDecimal("100");
        }
        if (dailyOrderCount <= 0) {
            dailyOrderCount = 20;
        }
        symbolAllowlist = symbolAllowlist == null ? List.of() : List.copyOf(symbolAllowlist);
    }
}
