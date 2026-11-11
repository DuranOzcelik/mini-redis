package miniredis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** TCP server speaking the Redis protocol. One virtual thread per connection. */
public final class Server implements AutoCloseable {
    final Store store = new Store();
    final Stats stats = new Stats();
    final Commands commands = new Commands(store, stats);
    private final ServerSocket socket;

    public Server(int port) throws IOException {
        // Loopback only: there is no AUTH, so the server must not be reachable from the network.
        socket = new ServerSocket(port, 511, InetAddress.getLoopbackAddress());
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void start() {
        Thread.ofPlatform().name("accept").start(this::acceptLoop);
        Thread.ofVirtual().name("housekeeping").start(this::housekeeping);
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                // Virtual threads make blocking I/O cheap, so each client gets plain sequential code.
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void housekeeping() {
        while (!socket.isClosed()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            store.sweep();
            stats.sample();
        }
    }

    private void serve(Socket client) {
        stats.clients.incrementAndGet();
        try (client) {
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 64 * 1024);
            while (true) {
                List<byte[]> args;
                try {
                    args = Resp.readCommand(in);
                } catch (Resp.ProtocolException e) {
                    Resp.error(out, "ERR Protocol error: " + e.getMessage());
                    out.flush();
                    return;
                }
                if (args == null) {
                    return;
                }
                if (new String(args.get(0), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT).equals("QUIT")) {
                    Resp.simple(out, "OK");
                    out.flush();
                    return;
                }
                commands.execute(args, out);
                // Pipelining: while more commands are already buffered, keep the replies in the
                // output buffer and send them all with one write when the input runs dry.
                if (in.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            stats.clients.decrementAndGet();
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        int dashboardPort = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        Server server = new Server(port);
        server.start();
        Dashboard.start(server, dashboardPort);
        System.out.println("mini-redis listening on 127.0.0.1:" + server.port());
        System.out.println("dashboard: http://127.0.0.1:" + dashboardPort);
    }
}
