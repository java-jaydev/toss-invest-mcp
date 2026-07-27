package dev.jaydev.tossmcp.trading;

import java.util.List;

/**
 * 주문 도구의 응답. 무엇이 일어날 예정인지(DRY_RUN) 또는 일어났는지(PLACED)가 항상 드러나야 한다.
 *
 * clientOrderId 는 토스의 멱등성 키다. UNKNOWN 인 경우 같은 값으로 10분 안에 다시 시도하면
 * 중복 주문 없이 같은 결과를 받는다.
 */
public record OrderResult(
        Status status,
        String reason,
        OrderPreview wouldPlace,
        List<String> guardrail,
        String orderId,
        String clientOrderId
) {
    public enum Status { DRY_RUN, PLACED, REJECTED, UNKNOWN }

    public record OrderPreview(
            String symbol,
            String side,
            String orderType,
            String quantity,
            String price,
            String estimatedNotional,
            String currency
    ) {}
}
