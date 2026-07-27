package dev.jaydev.tossmcp.trading;

import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.GuardDecision.Outcome;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 실주문 직전에 통과해야 하는 안전게이트.
 *
 * 판정 순서는 가드레일 → 킬스위치 → execute 플래그다. 가드레일을 먼저 보는 이유는
 * 한도 위반이 미리보기 단계에서도 REJECT 로 드러나야 하기 때문이다. 킬스위치를 먼저 보면
 * 한도를 넘는 주문도 "이렇게 주문됩니다"로 보이고, 실매매를 켠 순간에야 거부당한다.
 *
 * 네트워크·시계에 의존하지 않으므로 실 API 없이 전수 테스트할 수 있다.
 * 전략 판단은 하지 않는다 — 한도와 스위치만 본다.
 */
@Component
public class OrderGuard {

    private final TossTradingProperties props;

    public OrderGuard(TossTradingProperties props) {
        this.props = props;
    }

    /**
     * @param notional        추정 주문금액. 산출하지 못했으면 null 을 넘긴다(모르면 막는다).
     * @param currency        "KRW" 또는 "USD"
     * @param todayOrderCount 오늘 이미 보낸 주문 수
     */
    public GuardDecision evaluatePlace(OrderCommand cmd, BigDecimal notional, String currency, int todayOrderCount) {
        List<String> checks = new ArrayList<>();

        if (notional == null) {
            return new GuardDecision(Outcome.REJECT,
                    "주문 금액을 산출하지 못해(시세 조회 실패) 한도를 검증할 수 없습니다.", checks);
        }

        BigDecimal cap = "USD".equals(currency) ? props.maxOrderNotionalUsd() : props.maxOrderNotionalKrw();
        if (notional.compareTo(cap) > 0) {
            return new GuardDecision(Outcome.REJECT,
                    "주문 금액 " + notional.toPlainString() + " " + currency
                            + " 이 한도 " + cap.toPlainString() + " 을 넘습니다.", checks);
        }
        checks.add("주문금액 " + notional.toPlainString() + " " + currency
                + " 가 한도 " + cap.toPlainString() + " 이하");

        if (todayOrderCount >= props.dailyOrderCount()) {
            return new GuardDecision(Outcome.REJECT,
                    "오늘 주문 수가 한도 " + props.dailyOrderCount() + " 건에 도달했습니다.", checks);
        }
        checks.add("오늘 주문수 " + todayOrderCount + " 가 한도 " + props.dailyOrderCount() + " 미만");

        List<String> allowlist = props.symbolAllowlist();
        if (!allowlist.isEmpty() && !allowlist.contains(cmd.symbol())) {
            return new GuardDecision(Outcome.REJECT,
                    "종목 " + cmd.symbol() + " 은 허용목록에 없습니다.", checks);
        }
        checks.add(allowlist.isEmpty() ? "종목 허용목록 미설정(제한 없음)" : "종목이 허용목록에 있음");

        return killSwitchAndExecuteFlag(cmd.execute(), checks);
    }

    /**
     * 취소는 금액·횟수 가드레일 대상이 아니다. 한도로 취소를 막으면 원치 않는 포지션에
     * 갇히게 되어 오히려 위험하다. 킬스위치와 execute 플래그만 적용한다.
     */
    public GuardDecision evaluateCancel(boolean execute) {
        List<String> checks = new ArrayList<>();
        checks.add("취소는 금액·횟수 가드레일 대상이 아님");
        return killSwitchAndExecuteFlag(execute, checks);
    }

    private GuardDecision killSwitchAndExecuteFlag(boolean execute, List<String> checks) {
        if (!props.enabled()) {
            return new GuardDecision(Outcome.DRY_RUN,
                    "실매매가 꺼져 있습니다(toss.trading.enabled=false). 미리보기만 수행합니다.", checks);
        }
        if (!execute) {
            return new GuardDecision(Outcome.DRY_RUN,
                    "execute=true 가 아니므로 미리보기만 수행합니다.", checks);
        }
        return new GuardDecision(Outcome.ALLOW, "모든 안전게이트를 통과했습니다.", checks);
    }
}
