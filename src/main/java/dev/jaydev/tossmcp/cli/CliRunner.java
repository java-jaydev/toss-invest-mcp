package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.service.TradingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

/**
 * {@code cli} 프로파일 전용 실행기. picocli 명령을 한 번 실행하고 그 종료 코드로
 * 프로세스를 끝낸다. 판단은 하지 않는다 — 실행과 종료 코드 전달만 한다.
 */
@Component
@Profile("cli")
public class CliRunner implements CommandLineRunner {

    private final MarketDataService market;
    private final TradingService trading;

    public CliRunner(MarketDataService market, TradingService trading) {
        this.market = market;
        this.trading = trading;
    }

    @Override
    public void run(String... args) {
        CommandLine cli = TossCli.buildCommandLine(market, trading);
        int exitCode = cli.execute(args);
        System.exit(exitCode);
    }
}
