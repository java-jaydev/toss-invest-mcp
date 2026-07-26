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
 * 이제 application.yml 의 web-application-type: none 한 줄만이 톰캣을 막는다.
 * 그 줄이 사라지면 stdio 모드에서 배너·로그가 stdout 으로 새어 MCP 프로토콜이 깨진다.
 * YAML 문자열 파싱으로는 이 회귀를 못 잡으므로 실제 컨텍스트를 띄워 확인한다.
 */
class StdioNoWebServerTest {

    private void assertNoWebServer(ConfigurableApplicationContext ctx, String why) {
        assertThat(ctx).as(why).isNotInstanceOf(WebApplicationContext.class);
        assertThat(ctx.getBeanNamesForType(WebServer.class)).as(why).isEmpty();
    }

    @Test
    void defaultStartsNoWebServer() {
        SpringApplication app = new SpringApplication(TossMcpApplication.class);
        try (ConfigurableApplicationContext ctx = app.run()) {
            assertNoWebServer(ctx, "stdio 경로에서 웹 컨텍스트가 뜨면 stdout 이 오염된다");
        }
    }

    /**
     * 회귀 방지의 핵심. stdout 보호 설정을 stdio 프로파일에 넣어두면
     * SPRING_PROFILES_ACTIVE 에 무관한 값(prod, dev 등)이 들어오는 순간 보호가 통째로
     * 사라진다 — spring.profiles.default 는 활성 프로파일이 비어 있을 때만 적용되기 때문이다.
     * 실제로 그 구조에서 `SPRING_PROFILES_ACTIVE=dev` 로 띄우면 stdout 에 배너와
     * "Tomcat started on port 8080" 이 6KB 쏟아졌다.
     * 그래서 보호 설정은 application.yml 에 무조건 두고, 모르는 프로파일이 와도
     * 정상 stdio 로 떨어지게 한다.
     */
    @Test
    void unknownProfileStillStartsNoWebServer() {
        SpringApplication app = new SpringApplication(TossMcpApplication.class);
        app.setAdditionalProfiles("some-unrelated-profile");
        try (ConfigurableApplicationContext ctx = app.run()) {
            assertNoWebServer(ctx, "모르는 프로파일이 stdout 보호를 무너뜨리면 안 된다");
        }
    }
}
