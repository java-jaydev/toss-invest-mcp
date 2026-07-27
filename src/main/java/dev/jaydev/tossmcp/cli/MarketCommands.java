package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * 시세 조회 CLI 명령. {@link MarketDataService}에 그대로 위임하는 얇은 껍데기이며,
 * 전략 판단이나 별도 검증 로직은 두지 않는다.
 */
public final class MarketCommands {

    private MarketCommands() {
    }

    @Command(name = "price", description = "현재가를 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Price implements Callable<Integer> {
        private final MarketDataService market;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "종목 코드(콤마 구분, 최대 200)")
        String symbols;

        public Price(MarketDataService market) {
            this.market = market;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(market.prices(symbols));
            return 0;
        }
    }

    @Command(name = "orderbook", description = "호가(매수·매도 잔량)를 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Orderbook implements Callable<Integer> {
        private final MarketDataService market;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "종목 코드(단일)")
        String symbol;

        public Orderbook(MarketDataService market) {
            this.market = market;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(market.orderbook(symbol));
            return 0;
        }
    }

    @Command(name = "trades", description = "최근 체결 내역을 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Trades implements Callable<Integer> {
        private final MarketDataService market;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "종목 코드(단일)")
        String symbol;

        @Option(names = "--count", description = "조회 건수")
        Integer count;

        public Trades(MarketDataService market) {
            this.market = market;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(market.trades(symbol, count));
            return 0;
        }
    }

    @Command(name = "candles", description = "캔들(시가·고가·저가·종가) 차트를 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Candles implements Callable<Integer> {
        private final MarketDataService market;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "종목 코드(단일)")
        String symbol;

        @Option(names = "--interval", required = true, description = "간격: 1m 또는 1d")
        String interval;

        @Option(names = "--count", description = "봉 개수")
        Integer count;

        @Option(names = "--before", description = "페이지네이션 상한(ISO 8601 시각)")
        String before;

        @Option(names = "--adjusted", description = "수정주가 적용(기본은 서비스 기본값을 따름)")
        Boolean adjusted;

        public Candles(MarketDataService market) {
            this.market = market;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(market.candles(symbol, interval, count, before, adjusted));
            return 0;
        }
    }

    @Command(name = "stocks", description = "종목 기본 정보(이름·시장·종류·상장일 등)를 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Stocks implements Callable<Integer> {
        private final MarketDataService market;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "종목 코드(콤마 구분, 최대 200)")
        String symbols;

        public Stocks(MarketDataService market) {
            this.market = market;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(market.stocks(symbols));
            return 0;
        }
    }
}
