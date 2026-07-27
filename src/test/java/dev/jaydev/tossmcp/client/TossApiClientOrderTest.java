package dev.jaydev.tossmcp.client;

import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.config.TossProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TossApiClientOrderTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    private TossApiClient clientWithAccount(String account) {
        TossAuthService auth = mock(TossAuthService.class);
        when(auth.accessToken()).thenReturn("test-token");
        TossProperties props = new TossProperties(
                server.url("/").toString(), "test-id", "test-secret", account);
        return new TossApiClient(props, auth);
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    void placeOrderPostsBodyWithAccountHeader() throws Exception {
        enqueueJson("{\"result\":{\"orderId\":\"order-1\"}}");

        String response = clientWithAccount("42").placeOrder(Map.of(
                "symbol", "005930",
                "side", "BUY",
                "orderType", "MARKET",
                "quantity", "1"));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders");
        assertThat(request.getHeader("X-Tossinvest-Account")).isEqualTo("42");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(request.getBody().readUtf8()).contains("\"symbol\":\"005930\"");
        assertThat(response).contains("order-1");
    }

    @Test
    void cancelOrderPostsToCancelSubPath() throws Exception {
        enqueueJson("{\"result\":{\"orderId\":\"cancel-1\"}}");

        clientWithAccount("42").cancelOrder("abc-123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders/abc-123/cancel");
        assertThat(request.getHeader("X-Tossinvest-Account")).isEqualTo("42");
    }

    @Test
    void getOrdersSendsRequiredStatusQuery() throws Exception {
        enqueueJson("{\"result\":{\"items\":[]}}");

        clientWithAccount("42").getOrders("OPEN", null, null);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/api/v1/orders?status=OPEN");
        assertThat(request.getHeader("X-Tossinvest-Account")).isEqualTo("42");
    }

    @Test
    void getHoldingsAndBuyingPowerUseTheirOwnPaths() throws Exception {
        enqueueJson("{\"result\":{}}");
        clientWithAccount("42").getHoldings(null);
        RecordedRequest holdingsRequest = server.takeRequest();
        assertThat(holdingsRequest.getPath()).isEqualTo("/api/v1/holdings");
        assertThat(holdingsRequest.getHeader("X-Tossinvest-Account")).isEqualTo("42");

        enqueueJson("{\"result\":{}}");
        clientWithAccount("42").getBuyingPower("KRW");
        RecordedRequest buyingPowerRequest = server.takeRequest();
        assertThat(buyingPowerRequest.getPath()).isEqualTo("/api/v1/buying-power?currency=KRW");
        assertThat(buyingPowerRequest.getHeader("X-Tossinvest-Account")).isEqualTo("42");
    }

    @Test
    void missingAccountFailsWithClearMessage() {
        assertThatThrownBy(() -> clientWithAccount("").getHoldings(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_ACCOUNT");
    }
}
