package dev.jaydev.tossmcp.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jaydev.tossmcp.service.TradingService;
import dev.jaydev.tossmcp.trading.OrderResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

/**
 * 주문 CLI 명령. 판단은 전부 {@link TradingService}(그리고 그 안의 OrderGuard)에 맡긴다.
 * 여기서는 {@code --execute} 플래그를 있는 그대로 전달할 뿐, 기본값을 true 로 바꾸거나
 * 별도의 허용/거부 판단을 추가하지 않는다 — 그런 판단은 코어의 몫이다.
 *
 * <p>유일한 예외는 "지정가인데 가격이 없다" 는 사용법 오류다. 이는 안전 판단이 아니라
 * 인자 조합 검증이므로, 서비스를 부르기 전에 걸러내고 종료 코드 3(사용법 오류)을 돌려준다.
 */
public final class OrderCommands {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OrderCommands() {
    }

    @Command(name = "order", description = "매수·매도 주문을 낸다. --execute 없이는 미리보기(dry-run)다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Order implements Callable<Integer> {
        private final TradingService trading;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "매매 방향: buy 또는 sell")
        String side;

        @Parameters(index = "1", description = "종목 코드")
        String symbol;

        @Option(names = "--qty", required = true, description = "주문 수량")
        String quantity;

        @Option(names = "--type", required = true, description = "호가 유형: limit 또는 market")
        String orderType;

        @Option(names = "--price", description = "주문 가격. limit 일 때만 넣는다")
        String price;

        @Option(names = "--execute", description = "true 여야 실제로 전송한다. 기본은 미리보기")
        boolean execute;

        public Order(TradingService trading) {
            this.trading = trading;
        }

        @Override
        public Integer call() {
            if ("limit".equalsIgnoreCase(orderType) && (price == null || price.isBlank())) {
                throw new ParameterException(spec.commandLine(),
                        "--type limit 에는 --price 가 필요합니다.");
            }

            OrderResult result = trading.placeOrder(symbol, side, quantity, orderType, price, execute);
            return printAndMapExitCode(spec, result);
        }
    }

    @Command(name = "cancel", description = "접수된 주문을 취소한다. --execute 없이는 미리보기(dry-run)다.",
            exitCodeOnInvalidInput = 3, exitCodeOnExecutionException = 4)
    public static final class Cancel implements Callable<Integer> {
        private final TradingService trading;
        @Spec
        CommandSpec spec;

        @Parameters(index = "0", description = "취소할 주문의 식별자")
        String orderId;

        @Option(names = "--execute", description = "true 여야 실제로 취소한다. 기본은 미리보기")
        boolean execute;

        public Cancel(TradingService trading) {
            this.trading = trading;
        }

        @Override
        public Integer call() {
            OrderResult result = trading.cancelOrder(orderId, execute);
            return printAndMapExitCode(spec, result);
        }
    }

    /**
     * 사람이 읽을 수 있게 status·reason 을 먼저 찍고, 나머지 상세(미리보기·가드레일·주문번호)는
     * JSON 으로 덧붙인다. 종료 코드는 계약대로 status 에서 결정한다:
     * PLACED·DRY_RUN=0, REJECTED=1, UNKNOWN=2.
     */
    private static int printAndMapExitCode(CommandSpec spec, OrderResult result) {
        PrintWriter out = spec.commandLine().getOut();
        out.println("status: " + result.status());
        out.println("reason: " + result.reason());
        try {
            out.println(JSON.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            // status·reason 은 이미 위에서 출력했다. 상세 JSON 직렬화 실패는 치명적이지 않다.
        }

        return switch (result.status()) {
            case PLACED, DRY_RUN -> 0;
            case REJECTED -> 1;
            case UNKNOWN -> 2;
        };
    }
}
