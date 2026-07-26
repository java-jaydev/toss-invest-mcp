package dev.jaydev.tossmcp.vthread;

import com.sun.net.httpserver.HttpServer;
import dev.jaydev.tossmcp.auth.TossAuthService;
import dev.jaydev.tossmcp.cache.MarketDataCache;
import dev.jaydev.tossmcp.cache.NoOpL2Cache;
import dev.jaydev.tossmcp.config.TossProperties;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 핫패스가 JDK 21 가상스레드를 <b>핀(pin)</b>하지 않음을 경험적으로 검증한다.
 * "설정을 켰다"가 아니라 실제 실행 중 발생한 {@code jdk.VirtualThreadPinned}
 * JFR 이벤트를 in-process 로 세어 단언한다. -D 플래그가 forked 테스트 JVM 에
 * 닿지 않는 문제를 피하려고 녹화를 코드에서 직접 켠다.
 *
 * <p>블로킹이 {@code synchronized} 모니터 안에서 일어나면 캐리어 스레드가
 * 풀려나지 못하고 핀된다. 두 지점(캐시 로더의 Caffeine 모니터, 토큰 갱신의
 * synchronized HTTP)이 그 패턴이며, 이 테스트는 수정 전 RED·수정 후 GREEN 이다.
 */
class VirtualThreadPinningTest {

    private record PinReport(long count, String sample) {
    }

    /** 워크로드 실행 동안 발생한 가상스레드 핀 이벤트를 센다. */
    private PinReport pinsWhile(ThrowingRunnable workload) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1)).withStackTrace();
            recording.start();
            workload.run();
            recording.stop();

            Path file = Files.createTempFile("vt-pinning", ".jfr");
            try {
                recording.dump(file);
                List<RecordedEvent> pins = RecordingFile.readAllEvents(file).stream()
                        .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                        .toList();
                String sample = pins.isEmpty() ? "(없음)" : describe(pins.get(0));
                return new PinReport(pins.size(), sample);
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String describe(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return event.toString();
        }
        return event.getStackTrace().getFrames().stream()
                .limit(8)
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                .collect(Collectors.joining(" <- "));
    }

    /** n 개의 가상스레드를 동시에 출발시키고 모두 끝날 때까지 기다린다. */
    private static void runConcurrentVirtualThreads(int n, ThrowingIntConsumer task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.accept(idx);
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }
        if (firstError.get() != null) {
            throw new RuntimeException("워크로드 스레드에서 예외 발생", firstError.get());
        }
    }

    @Test
    void cacheHotPathDoesNotPinVirtualThreads() throws Exception {
        MarketDataCache cache = new MarketDataCache(new NoOpL2Cache());

        PinReport report = pinsWhile(() ->
                runConcurrentVirtualThreads(32, i ->
                        cache.get("key-" + i, Duration.ofSeconds(60), () -> {
                            sleepMillis(50); // upstream(토스 API) 지연을 흉내낸 블로킹
                            return "v" + i;
                        })));

        assertThat(report.count())
                .as("캐시 로더가 Caffeine 의 ConcurrentHashMap 모니터 안에서 블로킹하면 캐리어가 핀된다. "
                        + "핀 %d 건, 예: %s", report.count(), report.sample())
                .isZero();
    }

    @Test
    void tokenRefreshDoesNotPinVirtualThreads() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            sleepMillis(50); // 토큰 발급 왕복 지연을 흉내낸 블로킹
            byte[] body = "{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            TossAuthService auth = new TossAuthService(new TossProperties(baseUrl, "id", "secret", "acct"));

            PinReport report = pinsWhile(() ->
                    runConcurrentVirtualThreads(8, i -> auth.accessToken()));

            assertThat(report.count())
                    .as("토큰 갱신 HTTP 가 synchronized 안에서 블로킹하면 캐리어가 핀된다. "
                            + "핀 %d 건, 예: %s", report.count(), report.sample())
                    .isZero();
        } finally {
            server.stop(0);
        }
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int i) throws Exception;
    }
}
