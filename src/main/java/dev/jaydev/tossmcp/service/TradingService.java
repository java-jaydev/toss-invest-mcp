package dev.jaydev.tossmcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaydev.tossmcp.client.TossApiClient;
import dev.jaydev.tossmcp.trading.DailyOrderCounter;
import dev.jaydev.tossmcp.trading.GuardDecision;
import dev.jaydev.tossmcp.trading.OrderCommand;
import dev.jaydev.tossmcp.trading.OrderCommand.OrderType;
import dev.jaydev.tossmcp.trading.OrderCommand.Side;
import dev.jaydev.tossmcp.trading.OrderGuard;
import dev.jaydev.tossmcp.trading.OrderRateLimiter;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.OrderPreview;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 주문 도구의 오케스트레이션. 입력 검증 → 금액 추정 → 안전게이트 → 전송 → 응답 정규화.
 *
 * 전략 판단은 하지 않는다. 무엇을 살지 팔지는 이 서버를 쓰는 두뇌가 정한다.
 *
 * 쓰기 경로는 캐시와 요청병합을 타지 않는다. 서로 다른 두 주문이 하나로 병합되면 재앙이다.
 * 계좌 상태 조회도 캐시하지 않는다 — 방금 낸 주문이 보이지 않으면 두뇌가 중복 주문을 낸다.
 */
@Service
public class TradingService {

    /** 국내 종목은 6자리 숫자, 그 밖은 미국 티커로 본다. */
    private static final Pattern KOREAN_SYMBOL = Pattern.compile("\\d{6}");

    private final TossApiClient api;
    private final MarketDataService market;
    private final OrderGuard guard;
    private final DailyOrderCounter counter;
    private final OrderRateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();

    public TradingService(TossApiClient api, MarketDataService market, OrderGuard guard,
                          DailyOrderCounter counter, OrderRateLimiter limiter) {
        this.api = api;
        this.market = market;
        this.guard = guard;
        this.counter = counter;
        this.limiter = limiter;
    }

    public OrderResult placeOrder(String symbol, String side, String quantity,
                                  String orderType, String price, Boolean execute) {
        OrderCommand cmd;
        try {
            cmd = validate(symbol, side, quantity, orderType, price, execute);
        } catch (IllegalArgumentException e) {
            return new OrderResult(Status.REJECTED, e.getMessage(), null, List.of(), null, null);
        }

        String currency = KOREAN_SYMBOL.matcher(cmd.symbol()).matches() ? "KRW" : "USD";
        BigDecimal notional = estimateNotional(cmd);
        OrderPreview preview = new OrderPreview(
                cmd.symbol(),
                cmd.side().name(),
                cmd.orderType().name(),
                cmd.quantity().toPlainString(),
                cmd.price() == null ? null : cmd.price().toPlainString(),
                notional == null ? null : notional.toPlainString(),
                currency);

        GuardDecision decision = guard.evaluatePlace(cmd, notional, currency, counter.current());
        switch (decision.outcome()) {
            case REJECT -> {
                return new OrderResult(Status.REJECTED, decision.reason(), preview, decision.checks(), null, null);
            }
            case DRY_RUN -> {
                return new OrderResult(Status.DRY_RUN, decision.reason(), preview, decision.checks(), null, null);
            }
            case ALLOW -> { /* 아래로 진행 */ }
        }

        if (!limiter.tryAcquire()) {
            return new OrderResult(Status.REJECTED,
                    "주문 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.", preview, decision.checks(), null, null);
        }

        String clientOrderId = "mcp-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientOrderId", clientOrderId);
        body.put("symbol", cmd.symbol());
        body.put("side", cmd.side().name());
        body.put("orderType", cmd.orderType().name());
        body.put("quantity", cmd.quantity().toPlainString());
        if (cmd.orderType() == OrderType.LIMIT) {
            body.put("price", cmd.price().toPlainString());
        }
        // confirmHighValueOrder 는 일부러 넣지 않는다. 1억원 이상 주문을 토스가 막아주는
        // 장치인데 우리가 자동으로 켜면 그 보호가 사라진다.

        // 한도는 보수적으로 센다: 보냈으면 셌다고 본다(응답을 못 받아도 접수됐을 수 있다).
        counter.increment();
        try {
            String raw = api.placeOrder(body);
            return new OrderResult(Status.PLACED, "주문이 접수되었습니다.", preview, decision.checks(),
                    extractOrderId(raw), clientOrderId);
        } catch (RestClientResponseException e) {
            return new OrderResult(Status.REJECTED, describeApiError(e), preview, decision.checks(),
                    null, clientOrderId);
        } catch (ResourceAccessException e) {
            return new OrderResult(Status.UNKNOWN,
                    "응답을 받지 못해 접수 여부를 알 수 없습니다. getOpenOrders 로 확인하거나, "
                            + "같은 clientOrderId(" + clientOrderId + ")로 10분 안에 다시 시도하면 "
                            + "중복 주문 없이 같은 결과를 받습니다.",
                    preview, decision.checks(), null, clientOrderId);
        }
    }

    public OrderResult cancelOrder(String orderId, Boolean execute) {
        if (orderId == null || orderId.isBlank()) {
            return new OrderResult(Status.REJECTED, "orderId 가 필요합니다.", null, List.of(), null, null);
        }

        GuardDecision decision = guard.evaluateCancel(Boolean.TRUE.equals(execute));
        if (decision.outcome() != GuardDecision.Outcome.ALLOW) {
            return new OrderResult(Status.DRY_RUN,
                    decision.reason() + " 취소 대상 주문: " + orderId, null, decision.checks(), orderId, null);
        }

        if (!limiter.tryAcquire()) {
            return new OrderResult(Status.REJECTED,
                    "주문 요청이 너무 잦습니다. 잠시 후 다시 시도하세요.", null, decision.checks(), orderId, null);
        }

        try {
            String raw = api.cancelOrder(orderId);
            return new OrderResult(Status.PLACED, "취소가 접수되었습니다.", null, decision.checks(),
                    extractOrderId(raw), null);
        } catch (RestClientResponseException e) {
            return new OrderResult(Status.REJECTED, describeApiError(e), null, decision.checks(), orderId, null);
        } catch (ResourceAccessException e) {
            return new OrderResult(Status.UNKNOWN,
                    "응답을 받지 못해 취소 접수 여부를 알 수 없습니다. getOpenOrders 로 확인하세요.",
                    null, decision.checks(), orderId, null);
        }
    }

    /** 계좌 상태 조회는 캐시하지 않는다. 주문 직후 값이 바뀌기 때문이다. */
    public String openOrders(String symbol, Integer limit) {
        return api.getOrders("OPEN", symbol, limit);
    }

    public String holdings(String symbol) {
        return api.getHoldings(symbol);
    }

    public String buyingPower(String currency) {
        return api.getBuyingPower(currency);
    }

    private OrderCommand validate(String symbol, String side, String quantity,
                                  String orderType, String price, Boolean execute) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 이 필요합니다.");
        }
        Side parsedSide = parseEnum(Side.class, side, "side 는 BUY 또는 SELL 이어야 합니다.");
        OrderType parsedType = parseEnum(OrderType.class, orderType,
                "orderType 은 LIMIT 또는 MARKET 이어야 합니다.");

        BigDecimal parsedQuantity = parseDecimal(quantity, "quantity");
        if (parsedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity 는 0보다 커야 합니다.");
        }

        BigDecimal parsedPrice = null;
        if (parsedType == OrderType.LIMIT) {
            if (price == null || price.isBlank()) {
                throw new IllegalArgumentException("지정가(LIMIT) 주문에는 price 가 필요합니다.");
            }
            parsedPrice = parseDecimal(price, "price");
            if (parsedPrice.signum() <= 0) {
                throw new IllegalArgumentException("price 는 0보다 커야 합니다.");
            }
        } else if (price != null && !price.isBlank()) {
            throw new IllegalArgumentException(
                    "시장가(MARKET) 주문에는 price 를 넣을 수 없습니다. 토스가 거부합니다.");
        }

        return new OrderCommand(symbol.trim(), parsedSide, parsedQuantity, parsedType,
                parsedPrice, Boolean.TRUE.equals(execute));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static BigDecimal parseDecimal(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " 가 필요합니다.");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 는 숫자여야 합니다: " + raw);
        }
    }

    /**
     * 추정 주문금액. 지정가는 가격이 확정돼 있고, 시장가는 최근 체결가로 추정한다.
     * 추정에 실패하면 null 을 돌려주고, 안전게이트가 이를 거부한다(모르면 막는다).
     */
    private BigDecimal estimateNotional(OrderCommand cmd) {
        if (cmd.orderType() == OrderType.LIMIT) {
            return cmd.price().multiply(cmd.quantity());
        }
        try {
            JsonNode lastPrice = json.readTree(market.prices(cmd.symbol()))
                    .path("result").path(0).path("lastPrice");
            if (lastPrice.isMissingNode() || lastPrice.asText().isBlank()) {
                return null;
            }
            return new BigDecimal(lastPrice.asText()).multiply(cmd.quantity());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractOrderId(String rawJson) {
        try {
            JsonNode id = json.readTree(rawJson).path("result").path("orderId");
            return id.isMissingNode() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토스의 에러 봉투를 사람이 읽을 수 있는 사유로 바꾼다. */
    private String describeApiError(RestClientResponseException e) {
        try {
            JsonNode error = json.readTree(e.getResponseBodyAsString()).path("error");
            String code = error.path("code").asText("");
            String message = error.path("message").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return "토스가 주문을 거부했습니다: " + message + " (" + code + ")";
            }
        } catch (Exception ignored) {
            // 봉투를 못 읽으면 상태 코드만 알린다.
        }
        return "토스가 주문을 거부했습니다: HTTP " + e.getStatusCode().value();
    }
}
