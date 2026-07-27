package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import dev.jaydev.tossmcp.trading.OrderResult.Status;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CLI 명령 트리를 스프링 컨텍스트 없이 picocli {@link CommandLine#execute(String...)} 로
 * 직접 검증한다. 서비스는 목으로 대체해, 각 명령이 정확한 인자로 위임하고 결과를 stdout 에
 * 쓰고 계약된 종료 코드를 돌려주는지만 확인한다.
 */
class CliCommandTest {

    private final MarketDataService market = mock(MarketDataService.class);
    private final TradingService trading = mock(TradingService.class);

    private StringWriter out;
    private StringWriter err;
    private CommandLine cli;

    private void setUp() {
        out = new StringWriter();
        err = new StringWriter();
        cli = TossCli.buildCommandLine(market, trading);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
    }

    private int execute(String... args) {
        setUp();
        return cli.execute(args);
    }

    // ---- price ----

    @Test
    void priceDelegatesToMarketDataServiceAndPrintsResult() {
        when(market.prices("005930")).thenReturn("{\"result\":[{\"lastPrice\":\"70000\"}]}");

        int exitCode = execute("price", "005930");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("70000");
        verify(market).prices("005930");
    }

    @Test
    void priceWithoutSymbolsIsAUsageErrorAndDoesNotCallTheService() {
        int exitCode = execute("price");

        assertThat(exitCode).isEqualTo(3);
        verifyNoInteractions(market);
    }

    @Test
    void unhandledExceptionFromTheServiceExitsWithCodeFour() {
        when(market.prices("005930")).thenThrow(new RuntimeException("네트워크 실패"));

        int exitCode = execute("price", "005930");

        assertThat(exitCode).isEqualTo(4);
    }

    // ---- orderbook ----

    @Test
    void orderbookDelegatesToMarketDataServiceAndPrintsResult() {
        when(market.orderbook("005930")).thenReturn("{\"result\":{}}");

        int exitCode = execute("orderbook", "005930");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("result");
        verify(market).orderbook("005930");
    }

    // ---- trades ----

    @Test
    void tradesDelegatesWithCountOption() {
        when(market.trades("005930", 10)).thenReturn("{\"result\":[]}");

        int exitCode = execute("trades", "005930", "--count", "10");

        assertThat(exitCode).isZero();
        verify(market).trades("005930", 10);
    }

    @Test
    void tradesWithoutCountPassesNull() {
        when(market.trades(eq("005930"), isNull())).thenReturn("{\"result\":[]}");

        int exitCode = execute("trades", "005930");

        assertThat(exitCode).isZero();
        verify(market).trades("005930", null);
    }

    // ---- candles ----

    @Test
    void candlesDelegatesWithAllOptions() {
        when(market.candles("005930", "1m", 50, "2026-07-28T00:00:00Z", true))
                .thenReturn("{\"result\":[]}");

        int exitCode = execute("candles", "005930", "--interval", "1m", "--count", "50",
                "--before", "2026-07-28T00:00:00Z", "--adjusted");

        assertThat(exitCode).isZero();
        verify(market).candles("005930", "1m", 50, "2026-07-28T00:00:00Z", true);
    }

    @Test
    void candlesWithoutAdjustedFlagPassesNull() {
        when(market.candles(eq("005930"), eq("1d"), any(), isNull(), isNull()))
                .thenReturn("{\"result\":[]}");

        int exitCode = execute("candles", "005930", "--interval", "1d");

        assertThat(exitCode).isZero();
        verify(market).candles("005930", "1d", null, null, null);
    }

    @Test
    void candlesWithoutIntervalIsAUsageErrorAndDoesNotCallTheService() {
        int exitCode = execute("candles", "005930");

        assertThat(exitCode).isEqualTo(3);
        verifyNoInteractions(market);
    }

    // ---- stocks ----

    @Test
    void stocksDelegatesToMarketDataServiceAndPrintsResult() {
        when(market.stocks("005930,000660")).thenReturn("{\"result\":[]}");

        int exitCode = execute("stocks", "005930,000660");

        assertThat(exitCode).isZero();
        verify(market).stocks("005930,000660");
    }

    // ---- unknown subcommand ----

    @Test
    void unknownSubcommandIsAUsageError() {
        int exitCode = execute("bogus");

        assertThat(exitCode).isEqualTo(3);
        verifyNoInteractions(market);
        verifyNoInteractions(trading);
    }

    // ---- holdings / buying-power / orders ----

    @Test
    void holdingsDelegatesToTradingServiceAndPrintsResult() {
        when(trading.holdings(isNull())).thenReturn("{\"result\":{}}");

        int exitCode = execute("holdings");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("result");
        verify(trading).holdings(null);
    }

    @Test
    void holdingsWithSymbolOptionDelegatesTheSymbol() {
        when(trading.holdings("005930")).thenReturn("{\"result\":{}}");

        int exitCode = execute("holdings", "--symbol", "005930");

        assertThat(exitCode).isZero();
        verify(trading).holdings("005930");
    }

    @Test
    void buyingPowerDelegatesToTradingServiceAndPrintsResult() {
        when(trading.buyingPower("KRW")).thenReturn("{\"result\":{}}");

        int exitCode = execute("buying-power", "KRW");

        assertThat(exitCode).isZero();
        verify(trading).buyingPower("KRW");
    }

    @Test
    void buyingPowerWithoutCurrencyIsAUsageErrorAndDoesNotCallTheService() {
        int exitCode = execute("buying-power");

        assertThat(exitCode).isEqualTo(3);
        verifyNoInteractions(trading);
    }

    @Test
    void ordersDelegatesWithSymbolAndLimitOptions() {
        when(trading.openOrders("005930", 5)).thenReturn("{\"result\":[]}");

        int exitCode = execute("orders", "--symbol", "005930", "--limit", "5");

        assertThat(exitCode).isZero();
        verify(trading).openOrders("005930", 5);
    }

    // ---- order ----

    @Test
    void orderWithoutExecuteFlagPassesExecuteFalse() {
        when(trading.placeOrder(eq("005930"), eq("buy"), eq("1"), eq("limit"), eq("50000"), eq(false)))
                .thenReturn(new OrderResult(Status.DRY_RUN, "미리보기입니다.", null, List.of(), null, null));

        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "limit", "--price", "50000");

        assertThat(exitCode).isZero();
        verify(trading).placeOrder("005930", "buy", "1", "limit", "50000", false);
    }

    @Test
    void orderWithExecuteFlagPassesExecuteTrue() {
        when(trading.placeOrder(eq("005930"), eq("buy"), eq("1"), eq("limit"), eq("50000"), eq(true)))
                .thenReturn(new OrderResult(Status.PLACED, "주문이 접수되었습니다.", null, List.of(), "ord-1", "cid-1"));

        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "limit", "--price", "50000",
                "--execute");

        assertThat(exitCode).isZero();
        verify(trading).placeOrder("005930", "buy", "1", "limit", "50000", true);
    }

    @Test
    void orderRejectedByTheCoreExitsWithCodeOne() {
        when(trading.placeOrder(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrderResult(Status.REJECTED, "일일 주문 한도를 초과했습니다.", null,
                        List.of("daily-count: FAIL"), null, null));

        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "limit", "--price", "50000",
                "--execute");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("status: REJECTED").contains("일일 주문 한도를 초과했습니다.");
    }

    @Test
    void orderUnknownFromTheCoreExitsWithCodeTwo() {
        when(trading.placeOrder(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OrderResult(Status.UNKNOWN, "응답을 받지 못했습니다.", null, List.of(), null, "cid-2"));

        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "market", "--execute");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("status: UNKNOWN").contains("응답을 받지 못했습니다.");
    }

    @Test
    void limitOrderWithoutPriceIsAUsageErrorAndDoesNotCallTheService() {
        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "limit");

        assertThat(exitCode).isEqualTo(3);
        verify(trading, never()).placeOrder(any(), any(), any(), any(), any(), any());
    }

    @Test
    void marketOrderWithoutPriceDoesNotNeedTheUsageCheck() {
        when(trading.placeOrder(eq("005930"), eq("buy"), eq("1"), eq("market"), isNull(), eq(false)))
                .thenReturn(new OrderResult(Status.DRY_RUN, "미리보기입니다.", null, List.of(), null, null));

        int exitCode = execute("order", "buy", "005930", "--qty", "1", "--type", "market");

        assertThat(exitCode).isZero();
        verify(trading).placeOrder("005930", "buy", "1", "market", null, false);
    }

    // ---- cancel ----

    @Test
    void cancelWithoutExecuteFlagIsAPreview() {
        when(trading.cancelOrder(eq("order-1"), eq(false)))
                .thenReturn(new OrderResult(Status.DRY_RUN, "미리보기입니다.", null, List.of(), "order-1", null));

        int exitCode = execute("cancel", "order-1");

        assertThat(exitCode).isZero();
        verify(trading).cancelOrder("order-1", false);
    }

    @Test
    void cancelWithExecuteFlagPassesExecuteTrue() {
        when(trading.cancelOrder(eq("order-1"), eq(true)))
                .thenReturn(new OrderResult(Status.PLACED, "취소가 접수되었습니다.", null, List.of(), "order-1", null));

        int exitCode = execute("cancel", "order-1", "--execute");

        assertThat(exitCode).isZero();
        verify(trading).cancelOrder("order-1", true);
    }

    @Test
    void cancelRejectedByTheCoreExitsWithCodeOne() {
        when(trading.cancelOrder(eq("order-1"), eq(true)))
                .thenReturn(new OrderResult(Status.REJECTED, "토스가 취소를 거부했습니다: 이미 체결됨 (E001)",
                        null, List.of(), "order-1", null));

        int exitCode = execute("cancel", "order-1", "--execute");

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString()).contains("status: REJECTED").contains("이미 체결됨");
    }

    @Test
    void cancelUnknownFromTheCoreExitsWithCodeTwo() {
        when(trading.cancelOrder(eq("order-1"), eq(true)))
                .thenReturn(new OrderResult(Status.UNKNOWN,
                        "응답을 받지 못해 취소 접수 여부를 알 수 없습니다.", null, List.of(), "order-1", null));

        int exitCode = execute("cancel", "order-1", "--execute");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).contains("status: UNKNOWN").contains("응답을 받지 못해");
    }

    // ---- CliRunner: --spring.* 인자는 picocli 로 넘어가기 전에 걸러져야 한다 ----
    // (실제 실행은 `java -jar ... --spring.profiles.active=cli <명령>` 형태이므로,
    // 이 인자가 그대로 picocli 에 들어가면 "Unknown option" 으로 항상 실패한다.)

    @Test
    void filterSpringArgsDropsSpringPrefixedArguments() {
        String[] filtered = CliRunner.filterSpringArgs(
                new String[] {"--spring.profiles.active=cli", "price", "005930"});

        assertThat(filtered).containsExactly("price", "005930");
    }

    @Test
    void filterSpringArgsKeepsOwnOptionsUntouched() {
        String[] filtered = CliRunner.filterSpringArgs(
                new String[] {"--spring.profiles.active=cli", "order", "buy", "005930",
                        "--qty", "1", "--type", "limit", "--price", "50000", "--execute"});

        assertThat(filtered).containsExactly(
                "order", "buy", "005930", "--qty", "1", "--type", "limit", "--price", "50000", "--execute");
    }

    @Test
    void cliRunnerExecuteIgnoresTheSpringProfileArgumentAndDelegates() {
        when(market.prices("005930")).thenReturn("{\"result\":[]}");
        CliRunner runner = new CliRunner(market, trading);

        int exitCode = runner.execute("--spring.profiles.active=cli", "price", "005930");

        assertThat(exitCode).isZero();
        verify(market).prices("005930");
    }
}
