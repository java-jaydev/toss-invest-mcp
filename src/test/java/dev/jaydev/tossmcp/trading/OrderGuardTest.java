package dev.jaydev.tossmcp.trading;

import dev.jaydev.tossmcp.config.TossTradingProperties;
import dev.jaydev.tossmcp.trading.GuardDecision.Outcome;
import dev.jaydev.tossmcp.trading.OrderCommand.OrderType;
import dev.jaydev.tossmcp.trading.OrderCommand.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderGuardTest {

    private static final BigDecimal KRW_CAP = new BigDecimal("100000");
    private static final BigDecimal USD_CAP = new BigDecimal("100");

    private OrderGuard guard(boolean enabled, List<String> allowlist) {
        return new OrderGuard(new TossTradingProperties(enabled, KRW_CAP, USD_CAP, 20, allowlist));
    }

    private OrderCommand buy(boolean execute) {
        return new OrderCommand("005930", Side.BUY, new BigDecimal("1"),
                OrderType.LIMIT, new BigDecimal("50000"), execute);
    }

    @Test
    void killSwitchOffForcesDryRunEvenWhenExecuteRequested() {
        GuardDecision d = guard(false, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(d.reason()).contains("toss.trading.enabled");
    }

    @Test
    void enabledWithoutExecuteFlagIsDryRun() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(false), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
    }

    @Test
    void enabledWithExecuteFlagIsAllowed() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void notionalOverCapIsRejectedEvenWhileTradingDisabled() {
        // 한도 위반은 미리보기에서도 드러나야 한다. 실매매를 켠 뒤에 놀라면 늦다.
        GuardDecision d = guard(false, List.of())
                .evaluatePlace(buy(false), new BigDecimal("100001"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(d.reason()).contains("한도");
    }

    @Test
    void notionalExactlyAtCapIsNotRejected() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), KRW_CAP, "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void unknownNotionalIsRejected() {
        // 시세를 못 구해 금액을 모르면 막는다.
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), null, "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void usdCapAppliesToUsdOrders() {
        OrderCommand aapl = new OrderCommand("AAPL", Side.BUY, new BigDecimal("1"),
                OrderType.LIMIT, new BigDecimal("101"), true);

        GuardDecision d = guard(true, List.of())
                .evaluatePlace(aapl, new BigDecimal("101"), "USD", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void dailyOrderCountAtLimitIsRejected() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 20);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }

    @Test
    void symbolOutsideAllowlistIsRejected() {
        GuardDecision d = guard(true, List.of("000660"))
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
        assertThat(d.reason()).contains("허용목록");
    }

    @Test
    void emptyAllowlistMeansNoRestriction() {
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "KRW", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void cancelIsNotSubjectToNotionalOrCountGuardrails() {
        // 취소를 한도로 막으면 포지션에 갇힌다.
        GuardDecision d = guard(true, List.of("000660")).evaluateCancel(true);

        assertThat(d.outcome()).isEqualTo(Outcome.ALLOW);
    }

    @Test
    void cancelStillRespectsKillSwitch() {
        GuardDecision d = guard(false, List.of()).evaluateCancel(true);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
    }

    @Test
    void cancelWithoutExecuteFlagIsDryRunWhenTradingEnabled() {
        GuardDecision d = guard(true, List.of()).evaluateCancel(false);

        assertThat(d.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(d.reason()).contains("execute=true");
    }

    @Test
    void unknownCurrencyIsRejectedRatherThanDefaultingToKrwCap() {
        // 통화가 "USD"/"KRW" 둘 다 아니면 한도를 판단할 수 없으니 막아야 한다(모르면 막는다).
        GuardDecision d = guard(true, List.of())
                .evaluatePlace(buy(true), new BigDecimal("50000"), "JPY", 0);

        assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
    }
}
