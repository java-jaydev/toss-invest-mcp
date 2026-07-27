package dev.jaydev.tossmcp.cli;

import dev.jaydev.tossmcp.service.MarketDataService;
import dev.jaydev.tossmcp.service.TradingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

import java.util.Arrays;

/**
 * {@code cli} 프로파일 전용 실행기. picocli 명령을 한 번 실행하고 그 종료 코드로
 * 프로세스를 끝낸다. 판단은 하지 않는다 — 실행과 종료 코드 전달만 한다.
 *
 * <p>{@link CommandLineRunner#run}은 {@code main(String[])}에 전달된 원본 인자를
 * 그대로 받는다. 여기에는 {@code --spring.profiles.active=cli}처럼 스프링이 소비하는
 * 인자도 섞여 있으므로, picocli 에 넘기기 전에 {@code --spring.} 로 시작하는 인자는
 * 걸러낸다. 그 밖의 인자(예: {@code --execute})는 손대지 않는다.
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
        System.exit(execute(args));
    }

    /**
     * 종료 코드만 계산하고 {@code System.exit}은 부르지 않는다 — 그래야 테스트가 프로세스를
     * 끝내지 않고 이 메서드를 검증할 수 있다.
     */
    int execute(String... args) {
        CommandLine cli = TossCli.buildCommandLine(market, trading);
        return cli.execute(filterSpringArgs(args));
    }

    /** 스프링이 소비하는 {@code --spring.*} 인자를 picocli 파싱 전에 제거한다. */
    static String[] filterSpringArgs(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--spring."))
                .toArray(String[]::new);
    }
}
