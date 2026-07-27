package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.service.TradingService;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

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
}
