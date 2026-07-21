# Phase 2.5 — Streamable HTTP 전송 + Java 21 가상스레드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** stdio 전용이던 MCP 서버에 Streamable HTTP 전송을 추가하고 Java 21 가상스레드로 요청을 처리한다. 아티팩트 하나가 프로파일에 따라 두 전송을 모두 서비스한다.

**Architecture:** 의존성을 `spring-ai-starter-mcp-server`(stdio 전용) → `spring-ai-starter-mcp-server-webmvc` 하나로 **교체**한다. webmvc 스타터가 `spring-ai-autoconfigure-mcp-server-common`(stdio 오토컨피그)을 전이 의존으로 품으므로 스타터 병기가 불필요하다. 전송 선택은 Spring 프로파일(`stdio` / `http`)로 가른다. `http` 프로파일에서만 톰캣이 뜨고 가상스레드가 켜진다.

**Tech Stack:** Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 · MCP Java SDK 0.18.3 · Gradle 8.14

## 왜 Phase 3보다 먼저인가

Phase 3의 산출물은 k6 부하테스트로 뽑는 "N req/s, p99, 캐시 오프로드 X%" 실측치다. k6는 HTTP로 부하를 준다. stdio 전송은 k6로 때릴 수 없으므로 HTTP 전송이 선행 조건이다.

## 사전 확인된 사실 (추측 아님 — 전부 실물 검증 완료)

| 사실 | 검증 방법 |
|---|---|
| Spring AI 1.1.8이 `STREAMABLE` 지원 | `McpServerProperties$ServerProtocol` enum을 `javap`로 확인 → `SSE`, `STREAMABLE`, `STATELESS` |
| 설정 키 이름·기본값 | 1.1.8 jar의 `META-INF/spring-configuration-metadata.json` 직독 |
| webmvc 스타터가 stdio 오토컨피그를 품음 | `spring-ai-autoconfigure-mcp-server-webmvc-1.1.8.pom`이 `-common` 의존 |
| MCP SDK 클라이언트가 클래스패스에 이미 있음 | `mcp-core-0.18.3.jar`에 `HttpClientStreamableHttpTransport.class` 존재 |
| 클라이언트 API 시그니처 | `javap`로 `builder(String)`, `.endpoint(String)`, `McpClient.sync(t).build()`, `initialize()`, `listTools()`, `callTool(CallToolRequest)` 확인 |
| Java 21 설치됨 | `/usr/lib/jvm/java-21-openjdk-amd64` |

**주의:** Spring AI **2.0 공식 문서에는 "STREAMABLE은 2.0에서 도입"이라고 적혀 있으나 이는 사실과 다르다.** 1.1.8 바이너리에 이미 존재한다. 문서 서술을 근거로 버전을 올리지 말 것.

## Global Constraints

- **기존 18개 테스트는 전부 통과 상태를 유지한다.** 하나라도 깨지면 그 태스크는 미완료다.
- **stdio 동작은 100% 보존한다.** 프로파일 미지정 시 현재와 완전히 동일하게 동작해야 한다(`spring.profiles.default: stdio`). 공개 저장소이고 README quickstart가 stdio 기준이다.
- **stdio 프로파일에서 stdout은 MCP 프로토콜 전용 채널이다.** 배너·콘솔로그가 stdout으로 새면 프로토콜이 깨진다.
- **시크릿을 코드·설정·테스트에 하드코딩하지 않는다.** 환경변수만 사용한다(`TOSS_CLIENT_ID`/`TOSS_CLIENT_SECRET`/`TOSS_ACCOUNT`).
- **통합테스트는 토스 실 API를 호출하지 않는다.** `TossApiClient`를 스텁으로 대체한다.
- **커밋 메시지에 AI 공동저자·생성 태그를 넣지 않는다.** 저자는 Jinkyu Lee.
- **`build.gradle`의 `testLogging { events 'passed','skipped','failed' }` 설정을 제거하지 않는다.** 테스트가 self-skip 돼도 초록불로 보이는 함정을 막는 장치다.
- 버전을 올리지 않는다: Spring Boot 3.5.16, Spring AI BOM 1.1.8 고정.

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `build.gradle` | 툴체인 21, 스타터 교체 | Modify |
| `src/main/resources/application.yml` | 전송 무관 공통 설정만 보유 | Modify |
| `src/main/resources/application-stdio.yml` | stdio 전송 전용 설정 | Create |
| `src/main/resources/application-http.yml` | HTTP 전송 + 가상스레드 설정 | Create |
| `src/test/java/dev/jaydev/tossmcp/RuntimeVersionTest.java` | 런타임이 21인지 고정 | Create |
| `src/test/java/dev/jaydev/tossmcp/transport/StdioProfileConfigTest.java` | stdio 프로파일 설정 회귀 방지 | Create |
| `src/test/java/dev/jaydev/tossmcp/transport/HttpTransportIT.java` | MCP SDK 클라이언트로 종단 왕복 | Create |
| `src/test/java/dev/jaydev/tossmcp/transport/VirtualThreadProbeIT.java` | 요청이 가상스레드에서 처리되는지 실측 | Create |
| `README.md` | HTTP 실행법·프로파일 문서화, 로드맵 갱신 | Modify |

---

## Task 1: Java 21 툴체인 전환

**Files:**
- Modify: `build.gradle:10-14`
- Test: `src/test/java/dev/jaydev/tossmcp/RuntimeVersionTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: Java 21 런타임. 이후 모든 태스크가 가상스레드·최신 API를 쓸 수 있는 전제.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/dev/jaydev/tossmcp/RuntimeVersionTest.java`:

```java
package dev.jaydev.tossmcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가상스레드는 Java 21 이상에서만 동작한다. 툴체인이 조용히 내려가면
 * spring.threads.virtual.enabled 는 무시되고 성능 측정이 거짓말이 된다.
 * 이 테스트가 그 회귀를 막는다.
 */
class RuntimeVersionTest {

    @Test
    void runsOnJava21OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*RuntimeVersionTest*'`
Expected: FAIL — `expected: 17 to be greater than or equal to: 21`

- [ ] **Step 3: 툴체인 상향**

`build.gradle`의 java 블록을 다음으로 교체:

```gradle
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

- [ ] **Step 4: 통과 + 전체 회귀 확인**

Run: `./gradlew clean test`
Expected: PASS. 신규 1건 포함 전부 통과, 실패 0.
※ `RedisL2CacheIT`는 로컬에 Docker 데몬이 없어 self-skip 된다. 정상이며, 실제 실행은 PR을 열어 GitHub Actions에서 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add build.gradle src/test/java/dev/jaydev/tossmcp/RuntimeVersionTest.java
git commit -m "build: raise toolchain to Java 21 for virtual threads"
```

---

## Task 2: 전송 프로파일 분리 (stdio / http)

**Files:**
- Modify: `build.gradle:26-40` (의존성 교체)
- Modify: `src/main/resources/application.yml` (공통만 남김)
- Create: `src/main/resources/application-stdio.yml`
- Create: `src/main/resources/application-http.yml`
- Test: `src/test/java/dev/jaydev/tossmcp/transport/StdioProfileConfigTest.java`

**Interfaces:**
- Consumes: Task 1의 Java 21 툴체인
- Produces: 프로파일 `stdio`(기본)와 `http`. `http` 프로파일은 `/mcp` 엔드포인트에서 Streamable HTTP를 서비스하고 `spring.threads.virtual.enabled=true`를 켠다. Task 3·4가 `http` 프로파일 위에서 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/dev/jaydev/tossmcp/transport/StdioProfileConfigTest.java`:

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*StdioProfileConfigTest*'`
Expected: FAIL — `application-stdio.yml` 이 없어 `ClassPathResource` 로드에서 `IllegalStateException`(또는 sources 비어 있음 단언 실패)

- [ ] **Step 3: 의존성 교체**

`build.gradle`의 dependencies 블록에서 stdio 전용 스타터 줄을 webmvc 스타터로 **교체**한다. 두 스타터를 병기하지 않는다 — webmvc 스타터가 `spring-ai-autoconfigure-mcp-server-common`(stdio 오토컨피그)을 전이 의존으로 이미 품는다.

교체 전:
```gradle
    // MCP server (stdio) — Claude Code / Cursor / Codex / Hermes / OpenClaw 연결
    implementation 'org.springframework.ai:spring-ai-starter-mcp-server'
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'org.springframework.boot:spring-boot-starter-json'
    implementation 'org.springframework:spring-web'
```

교체 후:
```gradle
    // MCP server — 한 아티팩트로 stdio·Streamable HTTP 두 전송을 모두 서비스한다.
    // webmvc 스타터가 stdio 오토컨피그(-common)를 전이 의존으로 품으므로 스타터 병기 불필요.
    // 전송 선택은 Spring 프로파일(stdio / http)로 가른다.
    implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-json'
```

`spring-boot-starter`와 `spring-web`은 webmvc 스타터가 `spring-boot-starter-web`을 통해 끌고 오므로 명시 선언을 제거한다.

- [ ] **Step 4: 공통 설정만 남기기**

`src/main/resources/application.yml`을 다음으로 교체:

```yaml
# 전송 방식과 무관한 공통 설정만 둔다.
# 전송별 설정은 application-stdio.yml / application-http.yml 로 분리했다.
spring:
  profiles:
    # 프로파일을 지정하지 않으면 기존과 동일하게 stdio 로 뜬다(하위호환).
    default: stdio
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  ai:
    mcp:
      server:
        name: toss-invest-mcp
        version: 0.1.0
        instructions: >-
          토스증권 공식 Open API(https://openapi.tossinvest.com) 기반 MCP 서버.
          공식 API만 사용하며(비공식 WTS 미사용), 기본은 read-only 입니다.

toss:
  base-url: https://openapi.tossinvest.com
  client-id: ${TOSS_CLIENT_ID:}
  client-secret: ${TOSS_CLIENT_SECRET:}
  account: ${TOSS_ACCOUNT:}
  cache:
    l2:
      enabled: ${TOSS_CACHE_L2:false}
```

- [ ] **Step 5: stdio 프로파일 설정 작성**

`src/main/resources/application-stdio.yml`:

```yaml
# stdio 전송: stdout 을 MCP 프로토콜 채널로 쓴다.
# 배너·콘솔로그가 stdout 으로 새면 클라이언트의 JSON-RPC 파싱이 깨지므로
# 웹 서버를 띄우지 않고 로그는 파일로만 남긴다.
spring:
  main:
    web-application-type: none
    banner-mode: "off"
  ai:
    mcp:
      server:
        stdio: true

logging:
  threshold:
    console: "OFF"
  file:
    name: ./logs/toss-mcp.log
```

- [ ] **Step 6: http 프로파일 설정 작성**

`src/main/resources/application-http.yml`:

```yaml
# Streamable HTTP 전송(MCP 스펙 2025-03-26). SSE 는 이 스펙으로 대체됐다.
# 톰캣 요청 처리를 가상스레드로 돌린다 — 토스 API 응답 대기(IO 바운드)에서
# 플랫폼 스레드를 붙잡지 않으므로 동시 처리량이 크게 오른다.
spring:
  main:
    web-application-type: servlet
  threads:
    virtual:
      enabled: true
  ai:
    mcp:
      server:
        stdio: false
        protocol: STREAMABLE
        streamable-http:
          mcp-endpoint: /mcp

server:
  port: ${PORT:8080}
```

- [ ] **Step 7: 통과 + 전체 회귀 확인**

Run: `./gradlew clean test`
Expected: PASS. `StdioProfileConfigTest` 3건 포함 전부 통과, 실패 0.

- [ ] **Step 8: stdio 기동 수동 확인**

Run: `./gradlew bootRun 2>/dev/null | head -5`
Expected: stdout 에 배너·로그가 **전혀 없어야** 한다. 출력이 비어 있으면 정상(MCP 클라이언트 입력 대기). 로그는 `./logs/toss-mcp.log` 에만 쌓인다. 확인 후 Ctrl+C.

- [ ] **Step 9: 커밋**

```bash
git add build.gradle src/main/resources/ src/test/java/dev/jaydev/tossmcp/transport/StdioProfileConfigTest.java
git commit -m "feat(transport): split stdio/http profiles, swap in webmvc starter"
```

---

## Task 3: Streamable HTTP 종단 통합테스트

**Files:**
- Test: `src/test/java/dev/jaydev/tossmcp/transport/HttpTransportIT.java`

**Interfaces:**
- Consumes: Task 2의 `http` 프로파일, `/mcp` 엔드포인트
- Produces: HTTP 전송이 실제로 MCP 프로토콜을 말한다는 증거. Phase 3 부하테스트가 이 엔드포인트를 때린다.

**배경:** 손으로 JSON-RPC 를 조립하지 않는다. 공식 MCP 자바 SDK 클라이언트(`mcp-core` 0.18.3, 이미 클래스패스에 있음)로 진짜 핸드셰이크를 돈다. 검증된 API: `HttpClientStreamableHttpTransport.builder(String baseUrl)` → `.endpoint(String)` → `.build()`, `McpClient.sync(transport).requestTimeout(Duration).build()`, `initialize()`, `listTools()`, `callTool(new CallToolRequest(String name, Map<String,Object> args))`.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/dev/jaydev/tossmcp/transport/HttpTransportIT.java`:

```java
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
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests '*HttpTransportIT*'`
Expected: 이 시점에 Task 2가 이미 끝나 있으므로 **통과할 수도 있다.** 통과하면 그것이 곧 Task 2 구현이 옳았다는 증거이므로 그대로 Step 3으로 간다. 실패하면 실패 메시지를 근거로 Step 3에서 고친다.

- [ ] **Step 3: 실패 시에만 수정**

전형적인 실패 원인과 대응:
- 툴 이름이 `getPrices`가 아니라 다른 규칙으로 등록됨 → 실제 `listTools()` 출력을 로그로 찍어 확인한 뒤 단언을 실제 이름에 맞춘다. **단언을 느슨하게 만들어 통과시키지 말 것** — 실제 이름으로 정확히 고정한다.
- 초기화 타임아웃 → `requestTimeout` 을 늘리기 전에 서버 로그에서 진짜 원인을 먼저 확인한다.

- [ ] **Step 4: 통과 + 전체 회귀 확인**

Run: `./gradlew clean test`
Expected: PASS. `HttpTransportIT` 3건 포함 전부 통과, 실패 0.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/dev/jaydev/tossmcp/transport/HttpTransportIT.java
git commit -m "test(transport): verify streamable HTTP handshake and tool call round trip"
```

---

## Task 4: 런타임 실측 검증 (가상스레드 + stdio 톰캣 미기동)

**Files:**
- Test: `src/test/java/dev/jaydev/tossmcp/transport/VirtualThreadProbeIT.java`
- Test: `src/test/java/dev/jaydev/tossmcp/transport/StdioNoWebServerTest.java`

**Task 2 리뷰에서 추가된 요구사항:** webmvc 스타터로 교체하면서 서블릿·톰캣 클래스가 **stdio 경로에도 클래스패스에 상주**하게 됐다. 교체 전에는 서블릿 클래스가 아예 없어서 웹 서버가 뜰 수 없었지만, 지금은 `application-stdio.yml`의 `web-application-type: none` **한 줄만이** 톰캣을 막는다. 이 줄이 사라지면 stdio 모드에서 톰캣이 뜨고 배너·로그가 stdout 으로 새어 MCP 프로토콜이 깨진다. `StdioProfileConfigTest` 는 YAML 문자열만 파싱하므로 이 회귀를 잡지 못한다. 실제 컨텍스트를 띄워 막는다.

**Interfaces:**
- Consumes: Task 2의 `http` 프로파일(`spring.threads.virtual.enabled=true`)
- Produces: 가상스레드가 설정만 된 게 아니라 **실제로 요청을 처리한다**는 증거. Phase 3 성능 수치의 전제.

**배경:** `spring.threads.virtual.enabled=true`를 넣고도 실제로는 플랫폼 스레드로 도는 경우가 흔하다(툴체인이 21 미만이거나, 톰캣이 아닌 다른 컨테이너거나, 프로파일이 안 먹었거나). 설정값을 읽어 단언하는 건 증거가 아니다 — 진짜 요청을 받아 **처리 스레드 자신에게 물어야** 한다. 테스트 전용 컨트롤러를 두어 실측한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/dev/jaydev/tossmcp/transport/VirtualThreadProbeIT.java`:

```java
package dev.jaydev.tossmcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가상스레드가 "설정됐다"가 아니라 "실제로 요청을 처리한다"를 증명한다.
 * 설정값을 읽어 단언하는 것은 증거가 되지 못한다 — 요청을 처리하는
 * 스레드 자신에게 isVirtual() 을 묻는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
class VirtualThreadProbeIT {

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ThreadProbeController threadProbeController() {
            return new ThreadProbeController();
        }
    }

    @RestController
    static class ThreadProbeController {
        @GetMapping("/__test/thread")
        String describe() {
            Thread t = Thread.currentThread();
            return t.isVirtual() + ":" + t.getName();
        }
    }

    @Test
    void tomcatServesRequestsOnVirtualThreads(@org.springframework.beans.factory.annotation.Autowired
                                              TestRestTemplate rest) {
        String body = rest.getForObject("/__test/thread", String.class);

        assertThat(body).as("응답 처리 스레드가 가상스레드여야 한다. 실제 값: %s", body)
                .startsWith("true:");
    }
}
```

- [ ] **Step 2: 실패 확인 (설정이 진짜로 먹는지 반증)**

먼저 가상스레드를 끈 상태로 돌려 이 테스트가 **실제로 실패하는지** 확인한다 — 항상 통과하는 테스트는 증거가 아니다.

Run: `SPRING_THREADS_VIRTUAL_ENABLED=false ./gradlew test --tests '*VirtualThreadProbeIT*' --rerun-tasks`
Expected: **FAIL**, 실제값이 `false:http-nio-auto-1-exec-1` 같은 플랫폼 스레드 이름

> **함정 주의 — `-D` 로는 반증이 안 된다.**
> `./gradlew ... -Dspring.threads.virtual.enabled=false` 형태는 **Gradle JVM 에만** 걸리고
> 포크된 테스트 JVM 으로 전달되지 않는다(`build.gradle` 의 test 태스크에 `systemProperties`
> 전달 설정이 없다). 그래서 이 형태로 돌리면 설정이 꺼지지 않은 채 테스트가 그대로
> **통과**하고, 반증을 한 것처럼 착각하게 된다. 실측으로 확인된 사실:
> `-D` → PASSED(무효), 환경변수 → FAILED(유효).
> 이 저장소의 기존 교훈("초록불 ≠ 실행됨")과 같은 부류의 함정이다.

그다음 정상 실행:

Run: `./gradlew test --tests '*VirtualThreadProbeIT*' --rerun-tasks`
Expected: PASS, 응답이 `true:` 로 시작

`--rerun-tasks` 를 빼면 Gradle 이 up-to-date 로 판단해 테스트를 아예 돌리지 않고 `BUILD SUCCESSFUL` 을 낸다. 초록불을 실행 증거로 쓰려면 반드시 붙인다.

- [ ] **Step 3: 실패 시에만 수정**

`false:` 가 나오면 설정이 안 먹은 것이다. 확인 순서: ① `Runtime.version().feature()` 가 21인가 ② `@ActiveProfiles("http")` 가 실제로 적용됐는가(`application-http.yml` 로딩 여부) ③ `spring.threads.virtual.enabled` 가 컨텍스트에 반영됐는가. **단언을 완화해 통과시키지 말 것.**

- [ ] **Step 3b: stdio 모드에서 톰캣이 안 뜨는지 실제 컨텍스트로 검증**

`src/test/java/dev/jaydev/tossmcp/transport/StdioNoWebServerTest.java`:

```java
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
```

구현 시 주의:
- 이 테스트는 실제 애플리케이션을 띄운다. stdio 전송이 `System.in` 을 읽는 스레드를 만들지만 데몬 스레드이고 컨텍스트를 닫으므로 테스트는 끝난다. 만약 실제로 멈춘다면 원인을 보고하고 **테스트를 지우지 말고** 상의할 것.
- 토스 자격증명 없이 떠야 한다(`TossProperties` 기본값이 빈 문자열). 기동만 하고 API 호출은 하지 않는다.

- [ ] **Step 3c: 이 테스트가 진짜 감시하는지 반증**

`src/main/resources/application-stdio.yml` 에서 `web-application-type: none` 줄을 **임시로 지우고** 돌린다.

Run: `./gradlew test --tests '*StdioNoWebServerTest*'`
Expected: **FAIL** (톰캣이 뜨면서 웹 컨텍스트가 됨)

실패를 확인했으면 지웠던 줄을 **반드시 원복**하고 다시 돌려 통과를 확인한다. 실패하지 않는다면 그 테스트는 아무것도 지키지 못하는 것이므로 그 사실을 보고할 것.

- [ ] **Step 4: 통과 + 전체 회귀 확인**

Run: `./gradlew clean test`
Expected: PASS. 전부 통과, 실패 0.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/dev/jaydev/tossmcp/transport/VirtualThreadProbeIT.java
git commit -m "test(transport): prove requests are served on virtual threads"
```

---

## Task 5: 문서 갱신

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1~4의 최종 동작
- Produces: 공개 저장소 이용자가 두 전송을 다 쓸 수 있는 문서

- [ ] **Step 1: README 갱신**

다음 내용을 반영한다. 기존 문서 구조·말투를 따르고, 없는 기능을 적지 않는다.

1. **요구사항**: Java 17 → **Java 21** 로 수정
2. **전송 방식 절 신설** — 두 가지 실행법:

````markdown
## 전송 방식

한 아티팩트가 프로파일에 따라 두 전송을 모두 서비스합니다.

### stdio (기본)

Claude Code·Cursor·Codex 등 로컬 MCP 클라이언트용입니다. 프로파일을 지정하지
않으면 stdio 로 뜹니다.

```bash
./gradlew bootRun
```

### Streamable HTTP

원격 접속·부하테스트용입니다. `/mcp` 엔드포인트에서 MCP 스펙 2025-03-26의
Streamable HTTP 를 서비스하며, 요청은 Java 21 가상스레드로 처리합니다.

```bash
./gradlew bootRun --args='--spring.profiles.active=http'
# 기본 포트 8080, PORT 환경변수로 변경 가능
```
````

3. **로드맵**: Phase 2.5 를 완료로 표시하고, 다음이 Phase 3(k6 부하테스트 + 관측성)임을 명시

- [ ] **Step 2: 문서와 실제 동작 대조**

README 에 적은 두 명령을 **실제로 실행해** 문서대로 동작하는지 확인한다.

Run: `./gradlew bootRun --args='--spring.profiles.active=http'` (별도 터미널에서 `curl -i -X POST localhost:8080/mcp` 로 응답 확인 후 종료)
Expected: 404 가 아닌 MCP 응답(프로토콜 오류여도 엔드포인트가 살아 있음을 뜻함)

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs: document stdio/http transports and Java 21 requirement"
```

---

## 완료 조건

- [ ] 전체 테스트 통과, 실패 0 (신규 테스트 포함)
- [ ] stdio 기동 시 stdout 무오염 확인
- [ ] `/mcp` 에서 MCP 핸드셰이크 왕복 성공
- [ ] 요청 처리 스레드가 가상스레드임을 실측 확인
- [ ] PR 을 열어 GitHub Actions 에서 `RedisL2CacheIT` 포함 전체 초록불 확인 (로컬은 Docker 없어 self-skip)
