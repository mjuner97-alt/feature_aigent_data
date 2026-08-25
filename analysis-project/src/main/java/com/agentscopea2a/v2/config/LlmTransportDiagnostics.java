    package com.agentscopea2a.v2.config;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 在不改变 AgentScope OpenAI 客户端行为的前提下，记录 HTTP 传输边界的诊断日志。 */
@org.springframework.context.annotation.Configuration
public class LlmTransportDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(LlmTransportDiagnostics.class);
    private HttpTransport original;
    private DiagnosticTransport diagnostic;

    @PostConstruct
    public void install() {
        original = HttpTransportFactory.getDefault();
        if (original instanceof DiagnosticTransport) return;
        diagnostic = new DiagnosticTransport(original);
        HttpTransportFactory.setDefault(diagnostic);
        log.info("llm.http：LLM 传输诊断已启用，底层实现={}", original.getClass().getName());
    }

    @PreDestroy
    public void uninstall() {
        if (diagnostic != null && HttpTransportFactory.getDefault() == diagnostic) {
            HttpTransportFactory.setDefault(original);
        }
    }

    private static final class DiagnosticTransport implements HttpTransport {
        private static final ScheduledExecutorService WATCHDOG = Executors.newScheduledThreadPool(1, task -> {
            Thread thread = new Thread(task, "llm-transport-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        private final HttpTransport delegate;
        DiagnosticTransport(HttpTransport delegate) { this.delegate = delegate; }

        @Override
        public HttpResponse execute(HttpRequest request) throws HttpTransportException {
            long start = System.nanoTime();
            log.info("llm.http.execute：开始非流式请求，地址={}，方法={}", request.getUrl(), request.getMethod());
            try {
                HttpResponse response = delegate.execute(request);
                log.info("llm.http.execute：收到非流式响应，地址={}，状态码={}，耗时毫秒={}", request.getUrl(),
                        response.getStatusCode(), elapsedMs(start));
                return response;
            } catch (RuntimeException e) {
                log.warn("llm.http.execute：非流式请求异常，地址={}，耗时毫秒={}，异常类型={}，异常信息={}", request.getUrl(),
                        elapsedMs(start), e.getClass().getName(), e.getMessage());
                throw e;
            }
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            long start = System.nanoTime();
            AtomicBoolean first = new AtomicBoolean();
            AtomicLong lastData = new AtomicLong(start);
            ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                long sinceStart = elapsedMs(start);
                long sinceData = elapsedMs(lastData.get());
                if (!first.get()) {
                    log.warn("llm.http.stream：仍未收到首条原始数据，地址={}，已等待毫秒={}", request.getUrl(), sinceStart);
                } else {
                    log.info("llm.http.stream：流式响应仍在等待下一条数据，地址={}，距上一条数据空闲毫秒={}",
                            request.getUrl(), sinceData);
                }
            }, 5, 5, TimeUnit.SECONDS);
            log.info("llm.http.stream：开始流式请求，地址={}，方法={}，请求头名称={}", request.getUrl(), request.getMethod(),
                    request.getHeaders().keySet());
            return delegate.stream(request)
                    .doOnNext(line -> {
                        lastData.set(System.nanoTime());
                        if (first.compareAndSet(false, true)) {
                            log.info("llm.http.stream：收到首条原始数据，地址={}，耗时毫秒={}，内容={}", request.getUrl(),
                                    elapsedMs(start), abbreviate(line));
                        }
                    })
                    .doFinally(signal -> {
                        watchdog.cancel(false);
                        log.info("llm.http.stream：流式请求结束，信号={}，地址={}，总耗时毫秒={}，是否收到数据={}",
                                signal, request.getUrl(), elapsedMs(start), first.get());
                    });
        }

        @Override
        public void close() { delegate.close(); }

        private static long elapsedMs(long start) {
            return (System.nanoTime() - start) / 1_000_000L;
        }

        private static String abbreviate(String value) {
            if (value == null) return "<null>";
            String s = value.replaceAll("\\s+", " ");
            return s.length() <= 300 ? s : s.substring(0, 300) + "...";
        }
    }
}
