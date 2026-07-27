package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.service.TradingService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * CLI 최상위 명령. 서브명령은 {@link MarketDataService}·{@link TradingService}에 그대로
 * 위임하는 얇은 껍데기다. 안전게이트(OrderGuard)는 {@code TradingService} 안에만 있고
 * 여기서는 아무 판단도 하지 않는다 — 인자를 파싱하고, 서비스를 부르고, 결과를 출력하고,
 * 종료 코드를 돌려줄 뿐이다.
 *
 * <p>종료 코드 규약: 0=성공(조회 성공 또는 주문 PLACED·DRY_RUN), 1=REJECTED,
 * 2=UNKNOWN, 3=사용법 오류, 4=그 밖의 실패(인증·네트워크 등 처리되지 않은 예외).
 */
@Command(
        name = "toss",
        mixinStandardHelpOptions = true,
        version = "toss-invest-mcp CLI",
        description = "토스증권 Open API 명령줄 어댑터. AI 없이 시세를 조회하고 (게이트를 통과하면) 주문한다.",
        exitCodeOnInvalidInput = 3,
        exitCodeOnExecutionException = 4
)
public final class TossCli implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        // 서브명령 없이 호출되면 사용법을 보여준다. 아무 것도 짐작하지 않는다.
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /**
     * 서비스가 준비된 완전한 명령 트리를 만든다. 스프링 컨텍스트 없이도(테스트) 그대로
     * 구성할 수 있도록 정적 팩터리로 둔다.
     */
    public static CommandLine buildCommandLine(MarketDataService market, TradingService trading) {
        CommandLine cli = new CommandLine(new TossCli());
        cli.addSubcommand("price", new MarketCommands.Price(market));
        cli.addSubcommand("orderbook", new MarketCommands.Orderbook(market));
        cli.addSubcommand("trades", new MarketCommands.Trades(market));
        cli.addSubcommand("candles", new MarketCommands.Candles(market));
        cli.addSubcommand("stocks", new MarketCommands.Stocks(market));
        cli.addSubcommand("holdings", new AccountCommands.Holdings(trading));
        cli.addSubcommand("buying-power", new AccountCommands.BuyingPower(trading));
        cli.addSubcommand("orders", new AccountCommands.Orders(trading));
        cli.addSubcommand("order", new OrderCommands.Order(trading));
        cli.addSubcommand("cancel", new OrderCommands.Cancel(trading));
        return cli;
    }
}
