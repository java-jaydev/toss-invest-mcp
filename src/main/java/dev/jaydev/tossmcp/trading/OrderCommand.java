package dev.jaydev.tossmcp.trading;

import java.math.BigDecimal;

/**
 * 검증을 마친 주문 명령. 도구 파라미터(문자열)를 파싱·검증한 결과이며 전략 판단은 담지 않는다.
 */
public record OrderCommand(
        String symbol,
        Side side,
        BigDecimal quantity,
        OrderType orderType,
        BigDecimal price,
        boolean execute
) {
    public enum Side { BUY, SELL }

    public enum OrderType { LIMIT, MARKET }
}
