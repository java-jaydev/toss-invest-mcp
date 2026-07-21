package dev.jaydev.tossmcp.transport;

import dev.jaydev.tossmcp.TossMcpApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * webmvc 스타터로 교체한 뒤로 톰캣·서블릿 클래스가 stdio 경로에도 클래스패스에 상주한다.
 * 이제 application-stdio.yml 의 web-application-type: none 한 줄만이 톰캣을 막는다.
 * 그 줄이 사라지면 stdio 모드에서 배너·로그가 stdout 으로 새어 MCP 프로토콜이 깨진다.
 * YAML 문자열 파싱으로는 이 회귀를 못 잡으므로 실제 컨텍스트를 띄워 확인한다.
 */
class StdioNoWebServerTest {

    @Test
    void defaultProfileStartsNoWebServer() {
        SpringApplication app = new SpringApplication(TossMcpApplication.class);
        try (ConfigurableApplicationContext ctx = app.run()) {
            assertThat(ctx)
                    .as("stdio 경로에서 웹 컨텍스트가 뜨면 stdout 이 오염된다")
                    .isNotInstanceOf(WebApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(WebServer.class))
                    .as("stdio 경로에 웹 서버 빈이 있으면 안 된다")
                    .isEmpty();
        }
    }
}
