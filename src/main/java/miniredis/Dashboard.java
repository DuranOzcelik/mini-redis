package miniredis;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/** Local web dashboard: live stats, key browser and a command console. JDK HTTP server only. */
final class Dashboard {
    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");
    private static final int MAX_KEYS = 100;

    private Dashboard() {
    }

    static HttpServer start(Server server, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/")) {
                send(exchange, 404, "text/plain", "not found");
                return;
            }
            try (InputStream page = Dashboard.class.getResourceAsStream("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
            }
        });
        http.createContext("/api/stats", exchange -> send(exchange, 200, "application/json", stats(server)));
        http.createContext("/api/keys", exchange -> send(exchange, 200, "application/json", keys(server.store)));
        http.createContext("/api/exec", exchange -> {
            // A custom header cannot be sent cross-origin without a CORS preflight, which this
            // server never approves. That stops other websites from running commands via the browser.
            if (!exchange.getRequestMethod().equals("POST")
                    || exchange.getRequestHeaders().getFirst("X-Mini-Redis") == null) {
                send(exchange, 403, "text/plain", "forbidden");
                return;
            }
            String line = new String(exchange.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            List<byte[]> args = split(line);
            String reply = "";
            if (!args.isEmpty()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                server.commands.execute(args, buffer);
                reply = Resp.render(buffer.toByteArray());
            }
            send(exchange, 200, "application/json", "{\"reply\":" + json(reply) + "}");
        });
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return http;
    }

    private static String stats(Server server) {
        Runtime runtime = Runtime.getRuntime();
        return "{\"clients\":" + server.stats.clients.get()
                + ",\"commands\":" + server.stats.commands.sum()
                + ",\"keys\":" + server.store.size()
                + ",\"uptime_s\":" + (System.currentTimeMillis() - server.stats.startedAt) / 1000
                + ",\"memory_bytes\":" + (runtime.totalMemory() - runtime.freeMemory())
                + ",\"history\":[" + LongStream.of(server.stats.history()).mapToObj(Long::toString)
                        .collect(Collectors.joining(",")) + "]}";
    }

    private static String keys(Store store) {
        List<String> all = store.keys("*");
        String rows = all.stream().sorted().limit(MAX_KEYS).map(key -> {
            byte[] value = store.get(key);
            if (value == null) {
                return null; // expired between listing and reading
            }
            String preview = new String(value, 0, Math.min(value.length, 80), StandardCharsets.ISO_8859_1)
                    .replaceAll("[^\\x20-\\x7e]", ".");
            return "{\"key\":" + json(key) + ",\"ttl_ms\":" + store.ttlMillis(key)
                    + ",\"size\":" + value.length + ",\"preview\":" + json(preview) + "}";
        }).filter(row -> row != null).collect(Collectors.joining(","));
        return "{\"total\":" + all.size() + ",\"keys\":[" + rows + "]}";
    }

    /** Splits a console line into arguments. Double quotes group words: SET greeting "hello world". */
    static List<byte[]> split(String line) {
        List<byte[]> args = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(line);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            args.add(token.getBytes(StandardCharsets.UTF_8));
        }
        return args;
    }

    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
