package miniredis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talks to a real server over TCP, the way any Redis client would. */
class ServerTest {
    private Server server;

    @BeforeEach
    void startServer() throws IOException {
        server = new Server(0); // port 0: the OS picks a free port
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    /** Minimal client: encodes commands as RESP arrays and reads one raw reply. */
    private final class Client implements AutoCloseable {
        private final Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        private final InputStream in = new BufferedInputStream(socket.getInputStream());

        Client() throws IOException {
            socket.setSoTimeout(5_000);
        }

        void send(byte[]... args) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Resp.arrayHeader(out, args.length);
            for (byte[] arg : args) {
                Resp.bulk(out, arg);
            }
            socket.getOutputStream().write(out.toByteArray());
        }

        void sendRaw(String text) throws IOException {
            socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        }

        /** Reads one reply and returns it in redis-cli notation. */
        String read() throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            int type = in.read();
            raw.write(type);
            String head = readLine(raw);
            if (type == '$' && Integer.parseInt(head) >= 0) {
                raw.write(in.readNBytes(Integer.parseInt(head) + 2));
            } else if (type == '*') {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < Integer.parseInt(head); i++) {
                    all.append(i == 0 ? "" : "\n").append(i + 1).append(") ").append(read());
                }
                return Integer.parseInt(head) == 0 ? "(empty array)" : all.toString();
            }
            return Resp.render(raw.toByteArray());
        }

        byte[] readBulk() throws IOException {
            assertEquals('$', in.read());
            int length = Integer.parseInt(readLine(new ByteArrayOutputStream()));
            byte[] data = in.readNBytes(length);
            in.readNBytes(2);
            return data;
        }

        private String readLine(ByteArrayOutputStream copy) throws IOException {
            StringBuilder line = new StringBuilder();
            int b;
            while ((b = in.read()) != '\n') {
                if (b == -1) {
                    throw new IOException("connection closed");
                }
                copy.write(b);
                if (b != '\r') {
                    line.append((char) b);
                }
            }
            copy.write('\n');
            return line.toString();
        }

        String call(String... args) throws IOException {
            byte[][] encoded = new byte[args.length][];
            for (int i = 0; i < args.length; i++) {
                encoded[i] = args[i].getBytes(StandardCharsets.UTF_8);
            }
            send(encoded);
            return read();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void basicCommands() throws IOException {
        try (Client c = new Client()) {
            assertEquals("PONG", c.call("PING"));
            assertEquals("\"hello\"", c.call("ECHO", "hello"));
            assertEquals("(nil)", c.call("GET", "k"));
            assertEquals("OK", c.call("SET", "k", "v"));
            assertEquals("\"v\"", c.call("GET", "k"));
            assertEquals("(integer) 1", c.call("EXISTS", "k"));
            assertEquals("(integer) 1", c.call("DEL", "k", "missing"));
            assertEquals("(integer) 0", c.call("DBSIZE"));
        }
    }

    @Test
    void setOptionsAndTtl() throws IOException {
        try (Client c = new Client()) {
            assertEquals("OK", c.call("SET", "k", "v", "EX", "100"));
            assertEquals("(integer) 100", c.call("TTL", "k"));
            assertEquals("(nil)", c.call("SET", "k", "other", "NX"));
            assertEquals("OK", c.call("set", "k", "other", "xx", "px", "5000"));
            assertEquals("(integer) 5", c.call("TTL", "k"));
            assertEquals("(integer) 1", c.call("PERSIST", "k"));
            assertEquals("(integer) -1", c.call("TTL", "k"));
            assertEquals("(integer) -2", c.call("TTL", "missing"));
        }
    }

    @Test
    void errorsMatchRedis() throws IOException {
        try (Client c = new Client()) {
            assertEquals("(error) ERR wrong number of arguments for 'get' command", c.call("GET"));
            assertEquals("(error) ERR unknown command 'NOPE'", c.call("NOPE", "x"));
            assertEquals("(error) ERR syntax error", c.call("SET", "k", "v", "BOGUS"));
            assertEquals("(error) ERR syntax error", c.call("SET", "k", "v", "NX", "XX"));
            assertEquals("(error) ERR invalid expire time in 'set' command", c.call("SET", "k", "v", "EX", "0"));
            c.call("SET", "text", "abc");
            assertEquals("(error) ERR value is not an integer or out of range", c.call("INCR", "text"));
            c.call("SET", "max", Long.toString(Long.MAX_VALUE));
            assertEquals("(error) ERR increment or decrement would overflow", c.call("INCR", "max"));
        }
    }

    @Test
    void unknownCommandCannotInjectExtraReplyLines() throws IOException {
        try (Client c = new Client()) {
            assertEquals("(error) ERR unknown command 'X??+OK'", c.call("X\r\n+OK"));
            assertEquals("PONG", c.call("PING")); // the stream is still in sync
        }
    }

    @Test
    void valuesAreBinarySafe() throws IOException {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < 256; i++) {
            everyByte[i] = (byte) i;
        }
        try (Client c = new Client()) {
            c.send("SET".getBytes(), everyByte, everyByte);
            assertEquals("OK", c.read());
            c.send("GET".getBytes(), everyByte);
            assertArrayEquals(everyByte, c.readBulk());
        }
    }

    @Test
    void pipelinedCommandsAreAnsweredInOrder() throws IOException {
        try (Client c = new Client()) {
            c.sendRaw("*3\r\n$3\r\nSET\r\n$1\r\nn\r\n$1\r\n5\r\n*2\r\n$4\r\nINCR\r\n$1\r\nn\r\nGET n\r\n");
            assertEquals("OK", c.read());
            assertEquals("(integer) 6", c.read());
            assertEquals("\"6\"", c.read());
        }
    }

    @Test
    void protocolErrorClosesConnection() throws IOException {
        try (Client c = new Client()) {
            c.sendRaw("*1\r\n$abc\r\n");
            assertTrue(c.read().startsWith("(error) ERR Protocol error"));
            assertEquals(-1, c.in.read());
        }
    }

    @Test
    void incrIsAtomicAcrossConnections() throws Exception {
        int clients = 8;
        int perClient = 500;
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                jobs.add(pool.submit(() -> {
                    try (Client c = new Client()) {
                        for (int j = 0; j < perClient; j++) {
                            c.call("INCR", "counter");
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                job.get();
            }
        }
        try (Client c = new Client()) {
            assertEquals("\"" + clients * perClient + "\"", c.call("GET", "counter"));
        }
    }

    @Test
    void hotkeysReportsTheMostUsedKeys() throws IOException {
        try (Client c = new Client()) {
            c.call("SET", "rare", "1");
            for (int i = 0; i < 20; i++) {
                c.call("GET", "popular");
            }
            assertEquals("1) \"popular\"\n2) (integer) 20", c.call("HOTKEYS", "1"));
            assertEquals("(error) ERR value is not an integer or out of range", c.call("HOTKEYS", "many"));
        }
    }

    @Test
    void commandNamesWorkUnderTurkishLocale() throws IOException {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); // "ping".toUpperCase() would be "PİNG"
        try (Client c = new Client()) {
            assertEquals("PONG", c.call("ping"));
            assertEquals("OK", c.call("set", "i", "1"));
            assertEquals("(integer) 2", c.call("incr", "i"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
