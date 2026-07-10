package dev.jaydev.tossmcp;

import dev.jaydev.tossmcp.tools.MarketDataTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

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
}
