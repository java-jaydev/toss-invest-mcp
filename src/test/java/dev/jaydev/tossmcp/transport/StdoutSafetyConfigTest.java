package dev.jaydev.tossmcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stdio 전송은 stdout 을 MCP 프로토콜 채널로 쓴다. 배너나 콘솔로그가 stdout 으로 새면
 * 클라이언트가 JSON-RPC 파싱에 실패한다.
 *
 * StdioNoWebServerTest 가 "웹서버가 안 뜬다"를 실제 컨텍스트로 증명하지만, 배너와
 * 콘솔로그 차단까지는 보지 못한다(웹서버 없이도 둘 다 stdout 을 오염시킬 수 있다).
 * 그 두 가지를 지키는 건 이 테스트뿐이다.
 *
 * 또한 이 설정들이 **프로파일 없는** application.yml 에 있다는 사실 자체가 중요하다.
 * 프로파일 파일로 옮기는 순간 SPRING_PROFILES_ACTIVE 에 다른 값이 오면 보호가 사라진다.
 */
class StdoutSafetyConfigTest {

    private PropertySource<?> load(String file) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        assertThat(sources).isNotEmpty();
        return sources.get(0);
    }

    @Test
    void stdoutGuardsLiveInTheUnprofiledBaseConfig() throws IOException {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(base.getProperty("spring.main.banner-mode")).isEqualTo("off");
        assertThat(base.getProperty("spring.ai.mcp.server.stdio")).isEqualTo(true);
        assertThat(base.getProperty("logging.threshold.console")).isEqualTo("OFF");
    }

    @Test
    void httpProfileExplicitlyOverridesEveryStdoutGuard() throws IOException {
        PropertySource<?> http = load("application-http.yml");

        // 기본값이 stdio 안전 설정이므로 HTTP 로 가려면 넷 다 되돌려야 한다.
        // 하나라도 빠지면 HTTP 모드가 조용히 반쪽으로 동작한다.
        assertThat(http.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(http.getProperty("spring.main.banner-mode")).isEqualTo("console");
        assertThat(http.getProperty("spring.ai.mcp.server.stdio")).isEqualTo(false);
        assertThat(http.getProperty("logging.threshold.console")).isEqualTo("INFO");
    }
}
