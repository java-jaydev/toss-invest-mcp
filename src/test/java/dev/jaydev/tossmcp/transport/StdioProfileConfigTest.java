package dev.jaydev.tossmcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stdio 전송은 stdout 을 MCP 프로토콜 채널로 쓴다. 배너나 콘솔로그가
 * stdout 으로 새면 클라이언트가 JSON-RPC 파싱에 실패한다.
 * 프로파일 분리 리팩터링이 이 설정을 떨어뜨리지 않았는지 고정한다.
 */
class StdioProfileConfigTest {

    private PropertySource<?> load(String file) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        assertThat(sources).isNotEmpty();
        return sources.get(0);
    }

    @Test
    void stdioProfileKeepsStdoutCleanForProtocol() throws IOException {
        PropertySource<?> stdio = load("application-stdio.yml");

        assertThat(stdio.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(stdio.getProperty("spring.main.banner-mode")).isEqualTo("off");
        assertThat(stdio.getProperty("spring.ai.mcp.server.stdio")).isEqualTo(true);
        assertThat(stdio.getProperty("logging.threshold.console")).isEqualTo("OFF");
    }

    @Test
    void httpProfileEnablesStreamableAndVirtualThreads() throws IOException {
        PropertySource<?> http = load("application-http.yml");

        assertThat(http.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("STREAMABLE");
        assertThat(http.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")).isEqualTo("/mcp");
        assertThat(http.getProperty("spring.threads.virtual.enabled")).isEqualTo(true);
    }

    @Test
    void defaultProfileIsStdioSoExistingClientsKeepWorking() throws IOException {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.profiles.default")).isEqualTo("stdio");
    }
}
