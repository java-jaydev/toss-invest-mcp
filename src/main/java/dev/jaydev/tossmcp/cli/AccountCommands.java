package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.TradingService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * 계좌 조회 CLI 명령. {@link TradingService}의 읽기 전용 메서드에 그대로 위임한다.
 * 이 클래스는 어떤 안전게이트도 두지 않는다 — 조회는 게이트 대상이 아니다.
 */
public final class AccountCommands {

    private AccountCommands() {
    }

    @Command(name = "holdings", description = "보유 주식과 평가 손익을 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Holdings implements Callable<Integer> {
        private final TradingService trading;
        @Spec
        CommandSpec spec;

        @Option(names = "--symbol", description = "특정 종목만 볼 때의 종목 코드")
        String symbol;

        public Holdings(TradingService trading) {
            this.trading = trading;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(trading.holdings(symbol));
            return 0;
        }
    }

    @Command(name = "buying-power", description = "매수에 쓸 수 있는 금액을 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class BuyingPower implements Callable<Integer> {
        private final TradingService trading;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "통화: KRW 또는 USD")
        String currency;

        public BuyingPower(TradingService trading) {
            this.trading = trading;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(trading.buyingPower(currency));
            return 0;
        }
    }

    @Command(name = "orders", description = "아직 체결되지 않은 주문 목록을 조회한다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Orders implements Callable<Integer> {
        private final TradingService trading;
        @Spec
        CommandSpec spec;

        @Option(names = "--symbol", description = "특정 종목만 볼 때의 종목 코드")
        String symbol;

        @Option(names = "--limit", description = "조회 건수")
        Integer limit;

        public Orders(TradingService trading) {
            this.trading = trading;
        }

        @Override
        public Integer call() {
            spec.commandLine().getOut().println(trading.openOrders(symbol, limit));
            return 0;
        }
    }
}
