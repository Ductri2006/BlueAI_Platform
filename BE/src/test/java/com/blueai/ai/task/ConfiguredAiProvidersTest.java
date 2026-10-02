package com.blueai.ai.task;

import com.blueai.rag.chat.RagChatProperties;
import com.blueai.system.service.GeminiUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class ConfiguredAiProvidersTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void completionRecordsProviderReportedGeminiUsage() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 200, """
                    {"model":"gemini-test","choices":[{"message":{"content":"Done."}}],
                     "usage":{"prompt_tokens":91,"completion_tokens":14,"total_tokens":110}}
                    """));
            RagChatProperties rag = new RagChatProperties();
            rag.setLlmBaseUrl(upstream.baseUrl());
            rag.setLlmChatPath("/chat");
            rag.setLlmApiKey("test-secret");
            rag.setLlmModel("test-model");
            GeminiUsageService usage = mock(GeminiUsageService.class);
            ConfiguredAiProviders provider = new ConfiguredAiProviders(rag, MAPPER, new AiTaskProperties(), usage);

            assertEquals("Done.", provider.complete("system", "question", List.of(), deadline(Duration.ofSeconds(3))));
            verify(usage).record("gemini-test", 91, 14, 110);
        }
    }

    @Test
    void successfulCompletionSurvivesUsageTransactionFailures() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 200, """
                    {"model":"gemini-test","choices":[{"message":{"content":"Paid answer preserved."}}],
                     "usage":{"prompt_tokens":91,"completion_tokens":14,"total_tokens":110}}
                    """));
            for (var failure : List.of(new CannotCreateTransactionException("pool unavailable"),
                    new TransactionSystemException("usage commit failed"))) {
                RagChatProperties rag = new RagChatProperties();
                rag.setLlmBaseUrl(upstream.baseUrl());
                rag.setLlmChatPath("/chat");
                rag.setLlmApiKey("test-secret");
                rag.setLlmModel("test-model");
                GeminiUsageService usage = mock(GeminiUsageService.class);
                doThrow(failure).when(usage).record("gemini-test", 91, 14, 110);
                ConfiguredAiProviders provider = new ConfiguredAiProviders(rag, MAPPER, new AiTaskProperties(), usage);
                assertEquals("Paid answer preserved.",
                        provider.complete("system", "question", List.of(), deadline(Duration.ofSeconds(3))));
                verify(usage).record("gemini-test", 91, 14, 110);
            }
        }
    }

    @Test
    void completionSendsConfiguredAuthAndPreservesSystemHistoryUserOrder() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(readRequest(exchange));
                respond(exchange, 200, """
                        {"choices":[{"message":{"content":"A grounded answer."}}]}
                        """);
            });
            ConfiguredAiProviders provider = provider(upstream, true, false);

            String result = provider.complete("system instructions", "current question", List.of(
                    Map.of("role", "user", "content", "earlier question"),
                    Map.of("role", "assistant", "content", "earlier answer")
            ), deadline(Duration.ofSeconds(3)));

            assertEquals("A grounded answer.", result);
            assertEquals("Bearer test-secret", authorization.get());
            assertEquals("test-model", requestBody.get().path("model").asText());
            assertEquals(0.0, requestBody.get().path("temperature").asDouble());
            JsonNode messages = requestBody.get().path("messages");
            assertEquals(4, messages.size());
            assertEquals(List.of("system", "user", "assistant", "user"),
                    java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                            .map(message -> message.path("role").asText()).toList());
            assertEquals("system instructions", messages.get(0).path("content").asText());
            assertEquals("current question", messages.get(3).path("content").asText());
        }
    }

    @Test
    void retrievalSendsOwnerQueryAndAuthorizedDocumentIds() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.search(exchange -> {
                requestBody.set(readRequest(exchange));
                respond(exchange, 200, """
                        {"matches":[{"documentId":"doc-1","chunkId":"chunk-4","content":"Relevant evidence","filename":"guide.pdf"}]}
                        """);
            });
            ConfiguredAiProviders provider = provider(upstream, false, true);

            JsonNode matches = provider.search("owner-7", "reset my password",
                    List.of("doc-1", "doc-9"), deadline(Duration.ofSeconds(3)));

            assertEquals("owner-7", requestBody.get().path("userId").asText());
            assertEquals("reset my password", requestBody.get().path("query").asText());
            assertEquals(20, requestBody.get().path("limit").asInt());
            assertEquals(List.of("doc-1", "doc-9"), MAPPER.convertValue(
                    requestBody.get().path("allowedDocumentIds"), List.class));
            assertEquals("doc-1", matches.get(0).path("documentId").asText());
        }
    }

    @Test
    void upstreamErrorsAreNotConvertedToEmptyOrSuccessfulResults() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 429, "{\"error\":\"busy\"}"));
            AiTaskException rateLimited = assertThrows(AiTaskException.class,
                    () -> provider(upstream, true, false).complete("s", "u", List.of(), deadline(Duration.ofSeconds(3))));
            assertEquals("AI_PROVIDER_RATE_LIMITED", rateLimited.code);

            upstream.search(exchange -> respond(exchange, 500, "{\"matches\":[]}"));
            AiTaskException retrievalFailed = assertThrows(AiTaskException.class,
                    () -> provider(upstream, false, true).search("owner", "query", List.of("doc"), deadline(Duration.ofSeconds(3))));
            assertEquals("AI_RETRIEVAL_FAILED", retrievalFailed.code);
        }
    }

    @Test
    void malformedProviderPayloadsFailClosed() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 200, "{\"choices\":[]}"));
            AiTaskException malformedCompletion = assertThrows(AiTaskException.class,
                    () -> provider(upstream, true, false).complete("s", "u", List.of(), deadline(Duration.ofSeconds(3))));
            assertEquals("AI_INVALID_OUTPUT", malformedCompletion.code);

            upstream.search(exchange -> respond(exchange, 200, "{\"notMatches\":[]}"));
            AiTaskException malformedSearch = assertThrows(AiTaskException.class,
                    () -> provider(upstream, false, true).search("owner", "query", List.of("doc"), deadline(Duration.ofSeconds(3))));
            assertEquals("AI_INVALID_OUTPUT", malformedSearch.code);
        }
    }

    @Test
    void oversizedProviderBodiesAreRejected() throws Exception {
        String oversized = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"padding\":\""
                + "x".repeat(33 * 1024) + "\"}";
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 200, oversized));

            AiTaskException failure = assertThrows(AiTaskException.class,
                    () -> provider(upstream, true, false).complete("s", "u", List.of(), deadline(Duration.ofSeconds(3))));

            assertEquals("AI_INVALID_OUTPUT", failure.code);
        }
    }

    @Test
    void slowHeadersAndSlowBodyAreBothBoundedByTheDeadline() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> {
                upstream.blockUntilClosed();
                try { respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}"); }
                catch (IOException ignored) { }
            });
            assertDeadlineExceeded(() -> provider(upstream, true, false)
                    .complete("s", "u", List.of(), deadline(Duration.ofMillis(350))), 1_200);
            upstream.releaseBlocked();
            upstream.prepareBlock();

            upstream.chat(exchange -> {
                byte[] first = "{\"choices\":[".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var body = exchange.getResponseBody()) {
                    body.write(first);
                    body.flush();
                    upstream.blockUntilClosed();
                    body.write("{}]}".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) { }
            });
            assertDeadlineExceeded(() -> provider(upstream, true, false)
                    .complete("s", "u", List.of(), deadline(Duration.ofMillis(350))));
        }
    }

    @Test
    void continuousBodyDripCannotKeepResettingTheSocketReadTimeout() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var body = exchange.getResponseBody()) {
                    for (int i = 0; i < 60; i++) {
                        body.write(' ');
                        body.flush();
                        try { Thread.sleep(40); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                } catch (IOException ignored) { }
            });

            assertDeadlineExceeded(() -> provider(upstream, true, false)
                    .complete("s", "u", List.of(), deadline(Duration.ofMillis(350))), 1_200);
        }
    }

    @Test
    void completionCannotExceedBudgetAlreadySpentOnRetrieval() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.search(exchange -> {
                try { Thread.sleep(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                respond(exchange, 200, "{\"matches\":[]}");
            });
            upstream.chat(exchange -> {
                try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
            });
            ConfiguredAiProviders provider = provider(upstream, true, true);
            Instant sharedDeadline = deadline(Duration.ofMillis(900));

            provider.search("owner", "query", List.of(), sharedDeadline);
            AiTaskException failure = assertThrows(AiTaskException.class,
                    () -> provider.complete("s", "u", List.of(), sharedDeadline));

            assertEquals("AI_TASK_DEADLINE_EXCEEDED", failure.code);
        }
    }

    @Test
    void expiredDeadlineCallsReleaseEveryProviderCapacitySlot() throws Exception {
        try (StubUpstream upstream = new StubUpstream()) {
            upstream.chat(exchange -> respond(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"ready\"}}]}"));
            ConfiguredAiProviders provider = provider(upstream, true, false);
            Instant expired = Instant.now().minusMillis(1);

            for (int i = 0; i < 33; i++) {
                AiTaskException failure = assertThrows(AiTaskException.class,
                        () -> provider.complete("s", "u", List.of(), expired));
                assertEquals("AI_TASK_DEADLINE_EXCEEDED", failure.code);
            }

            assertEquals("ready", provider.complete("s", "u", List.of(), deadline(Duration.ofSeconds(3))));
        }
    }

    private static void assertDeadlineExceeded(ThrowingCall call) {
        assertDeadlineExceeded(call, 1_500);
    }

    private static void assertDeadlineExceeded(ThrowingCall call, long maximumElapsedMillis) {
        long started = System.nanoTime();
        AiTaskException failure = assertThrows(AiTaskException.class, call::run);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("AI_TASK_DEADLINE_EXCEEDED", failure.code);
        assertTrue(elapsedMillis < maximumElapsedMillis,
                "request should stop promptly; elapsed=" + elapsedMillis + "ms, limit=" + maximumElapsedMillis + "ms");
    }

    private static ConfiguredAiProviders provider(StubUpstream upstream, boolean completion, boolean retrieval) {
        RagChatProperties rag = new RagChatProperties();
        if (completion) {
            rag.setLlmBaseUrl(upstream.baseUrl());
            rag.setLlmChatPath("/chat");
            rag.setLlmApiKey("test-secret");
            rag.setLlmModel("test-model");
        }
        if (retrieval) {
            rag.setRetrievalBaseUrl(upstream.baseUrl());
            rag.setRetrievalSearchPath("/search");
        }
        return new ConfiguredAiProviders(rag, MAPPER, new AiTaskProperties());
    }

    private static Instant deadline(Duration duration) {
        return Instant.now().plus(duration);
    }

    private static JsonNode readRequest(StubExchange exchange) throws IOException {
        return MAPPER.readTree(exchange.getRequestBody());
    }

    private static void respond(StubExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingCall { void run() throws Exception; }

    @FunctionalInterface
    private interface ExchangeHandler { void handle(StubExchange exchange) throws IOException; }

    private static final class StubExchange implements AutoCloseable {
        private final Socket socket;
        private final Map<String, String> requestHeaders;
        private final byte[] requestBody;
        private final StubHeaders responseHeaders = new StubHeaders();
        private OutputStream responseBody;
        private boolean chunked;

        StubExchange(Socket socket, Map<String, String> requestHeaders, byte[] requestBody) {
            this.socket = socket;
            this.requestHeaders = requestHeaders;
            this.requestBody = requestBody;
        }
        StubHeaders getRequestHeaders() { return new StubHeaders(requestHeaders); }
        StubHeaders getResponseHeaders() { return responseHeaders; }
        InputStream getRequestBody() { return new ByteArrayInputStream(requestBody); }
        OutputStream getResponseBody() { return responseBody; }
        void sendResponseHeaders(int status, long length) throws IOException {
            OutputStream raw = socket.getOutputStream();
            chunked = length == 0;
            StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
                    .append(reason(status)).append("\r\n");
            responseHeaders.values.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            if (chunked) head.append("Transfer-Encoding: chunked\r\n");
            else head.append("Content-Length: ").append(length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            raw.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            raw.flush();
            responseBody = new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (chunked && length > 0) {
                        raw.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                        raw.write(bytes, offset, length);
                        raw.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else if (length > 0) raw.write(bytes, offset, length);
                }
                @Override public void flush() throws IOException { raw.flush(); }
                @Override public void close() throws IOException {
                    if (chunked) raw.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    raw.flush();
                    StubExchange.this.close();
                }
            };
        }
        @Override public void close() throws IOException { socket.close(); }
        private static String reason(int status) {
            return switch (status) {
                case 200 -> "OK";
                case 429 -> "Too Many Requests";
                case 500 -> "Internal Server Error";
                default -> "Response";
            };
        }
    }

    private static final class StubHeaders {
        private final Map<String, String> values;
        StubHeaders() { values = new ConcurrentHashMap<>(); }
        StubHeaders(Map<String, String> initial) { values = new ConcurrentHashMap<>(initial); }
        void add(String name, String value) { values.put(name, value); }
        String getFirst(String name) {
            return values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
    }

    private static final class StubUpstream implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "configured-ai-provider-test-upstream");
            thread.setDaemon(true);
            return thread;
        });
        private final Map<String, ExchangeHandler> handlers = new ConcurrentHashMap<>();
        private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch release = new CountDownLatch(1);
        private final Thread acceptor;

        StubUpstream() throws IOException {
            server = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(1000);
            handlers.put("/chat", exchange -> respond(exchange, 500, "unexpected chat request"));
            handlers.put("/search", exchange -> respond(exchange, 500, "unexpected search request"));
            acceptor = new Thread(this::acceptRequests, "configured-ai-provider-test-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void chat(ExchangeHandler handler) { handlers.put("/chat", handler); }
        void search(ExchangeHandler handler) { handlers.put("/search", handler); }
        void blockUntilClosed() {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        void releaseBlocked() { release.countDown(); }
        void prepareBlock() { release = new CountDownLatch(1); }

        private void acceptRequests() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    openSockets.add(socket);
                    executor.execute(() -> handle(socket));
                } catch (SocketException closed) {
                    return;
                } catch (IOException timeoutOrAcceptFailure) {
                    if (server.isClosed()) return;
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                String requestLine = readLine(input);
                if (requestLine == null) return;
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2) return;
                Map<String, String> requestHeaders = new ConcurrentHashMap<>();
                String line;
                while ((line = readLine(input)) != null && !line.isEmpty()) {
                    int separator = line.indexOf(':');
                    if (separator > 0) requestHeaders.put(line.substring(0, separator).trim().toLowerCase(), line.substring(separator + 1).trim());
                }
                int contentLength = Integer.parseInt(requestHeaders.getOrDefault("content-length", "0"));
                byte[] body = input.readNBytes(contentLength);
                StubExchange exchange = new StubExchange(socket, requestHeaders, body);
                String path = requestParts[1].split("\\?", 2)[0];
                ExchangeHandler handler = handlers.get(path);
                if (handler == null) respond(exchange, 404, "not found");
                else handler.handle(exchange);
            } catch (IOException | RuntimeException ignored) {
                try { socket.close(); } catch (IOException ignoredClose) { }
            } finally {
                openSockets.remove(socket);
            }
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    byte[] line = bytes.toByteArray();
                    return new String(line, 0, Math.max(0, line.length - 1), StandardCharsets.ISO_8859_1);
                }
                bytes.write(current);
                previous = current;
            }
            return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.ISO_8859_1);
        }

        @Override public void close() {
            release.countDown();
            try { server.close(); } catch (IOException ignored) { }
            openSockets.forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } });
            acceptor.interrupt();
            executor.shutdownNow();
        }
    }
}
