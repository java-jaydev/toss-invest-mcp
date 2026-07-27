package dev.jaydev.tossmcp;

import dev.jaydev.tossmcp.tools.MarketDataTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TossMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(TossMcpApplication.class, args);
    }

    /**
     * @Tool 로 표시된 메서드들을 MCP 도구로 등록한다.
     * 도구 그룹을 추가할 때마다 toolObjects 에 빈을 더한다.
     */
    @Bean
    ToolCallbackProvider tossTools(MarketDataTools marketDataTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketDataTools)
                .build();
    }

    /**
     * 시간에 의존하는 로직(주문 카운터·레이트리밋)이 테스트에서 시간을 고정할 수 있도록
     * 시계를 주입 가능한 빈으로 둔다.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
