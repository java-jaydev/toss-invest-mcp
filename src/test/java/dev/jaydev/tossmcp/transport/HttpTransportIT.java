package dev.jaydev.tossmcp.transport;

import dev.jaydev.tossmcp.client.TossApiClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streamable HTTP 전송 종단 검증. 공식 MCP 자바 SDK 클라이언트로
 * initialize → tools/list → tools/call 왕복을 실제로 돈다.
 * 토스 실 API 는 호출하지 않는다(TossApiClient 스텁).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
class HttpTransportIT {

    @LocalServerPort
    int port;

    @MockitoBean
    TossApiClient tossApiClient;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        BDDMockito.given(tossApiClient.getPrices("005930"))
                .willReturn("{\"stub\":\"005930\"}");

        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp")
                        .build();

        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void initializeHandshakeSucceedsOverStreamableHttp() {
        McpSchema.InitializeResult result = client.initialize();

        assertThat(result).isNotNull();
        assertThat(result.serverInfo().name()).isEqualTo("toss-invest-mcp");
    }

    @Test
    void allFiveMarketDataToolsAreExposed() {
        client.initialize();

        McpSchema.ListToolsResult tools = client.listTools();

        assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                .contains("getPrices", "getOrderbook", "getTrades", "getCandles", "getStocks");
    }

    @Test
    void toolCallRoundTripsThroughCacheToStubbedApi() {
        client.initialize();

        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest("getPrices", Map.of("symbols", "005930")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).isNotEmpty();
        assertThat(result.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class,
                text -> assertThat(text.text()).contains("005930"));

        // 이름이 "ThroughCache" 이므로 캐시를 실제로 거치는지도 확인한다.
        // 같은 심볼을 다시 호출해도 상위 API 는 한 번만 불려야 한다(TTL 2초 이내).
        client.callTool(new McpSchema.CallToolRequest("getPrices", Map.of("symbols", "005930")));
        Mockito.verify(tossApiClient, Mockito.times(1)).getPrices("005930");
    }
}
