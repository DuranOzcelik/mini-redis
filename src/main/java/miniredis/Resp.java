package miniredis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** RESP2, the Redis wire protocol: reading client commands and writing replies. */
final class Resp {
    // Limits stop a client from making the server allocate memory with a single header line.
    static final int MAX_BULK = 64 * 1024 * 1024;
    static final int MAX_ARGS = 1024 * 1024;
    private static final int MAX_LINE = 64 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};

    static final class ProtocolException extends IOException {
        ProtocolException(String message) {
            super(message);
        }
    }

    private Resp() {
    }

    /** Reads one command. Returns null when the client disconnects. */
    static List<byte[]> readCommand(InputStream in) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.charAt(0) != '*') { // inline command, e.g. typed into telnet: "PING\r\n"
                List<byte[]> args = new ArrayList<>();
                for (String part : line.trim().split("\\s+")) {
                    args.add(part.getBytes(StandardCharsets.ISO_8859_1));
                }
                return args;
            }
            int count = parseLength(line, MAX_ARGS, "multibulk length");
            if (count <= 0) {
                continue;
            }
            List<byte[]> args = new ArrayList<>(Math.min(count, 64));
            for (int i = 0; i < count; i++) {
                String header = readLine(in);
                if (header == null) {
                    return null;
                }
                if (header.isEmpty() || header.charAt(0) != '$') {
                    throw new ProtocolException("expected '$'");
                }
                int length = parseLength(header, MAX_BULK, "bulk length");
                if (length < 0) {
                    throw new ProtocolException("invalid bulk length");
                }
                // Length-prefixed, so the payload may contain any byte, including \r\n.
                byte[] data = in.readNBytes(length);
                if (data.length < length) {
                    return null;
                }
                if (in.read() != '\r' || in.read() != '\n') {
                    throw new ProtocolException("expected CRLF after bulk string");
                }
                args.add(data);
            }
            return args;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(32);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            if (line.size() >= MAX_LINE) {
                throw new ProtocolException("line too long");
            }
            line.write(b);
        }
        return null;
    }

    private static int parseLength(String line, int max, String what) throws ProtocolException {
        try {
            int value = Integer.parseInt(line.substring(1));
            if (value > max) {
                throw new ProtocolException("invalid " + what);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid " + what);
        }
    }

    static void simple(OutputStream out, String text) throws IOException {
        line(out, '+', text);
    }

    static void error(OutputStream out, String message) throws IOException {
        line(out, '-', message);
    }

    static void integer(OutputStream out, long value) throws IOException {
        line(out, ':', Long.toString(value));
    }

    static void arrayHeader(OutputStream out, int size) throws IOException {
        line(out, '*', Integer.toString(size));
    }

    /** Bulk string. A null value is the RESP "nil" reply. */
    static void bulk(OutputStream out, byte[] value) throws IOException {
        if (value == null) {
            line(out, '$', "-1");
            return;
        }
        line(out, '$', Integer.toString(value.length));
        out.write(value);
        out.write(CRLF);
    }

    private static void line(OutputStream out, char type, String text) throws IOException {
        out.write(type);
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
    }

    /** Formats an encoded reply the way redis-cli prints it. Used by the dashboard console. */
    static String render(byte[] reply) {
        StringBuilder out = new StringBuilder();
        render(reply, new int[]{0}, out, "");
        return out.toString();
    }

    private static void render(byte[] data, int[] pos, StringBuilder out, String indent) {
        if (pos[0] >= data.length) {
            return;
        }
        char type = (char) data[pos[0]++];
        int end = pos[0];
        while (end < data.length && data[end] != '\r') {
            end++;
        }
        String head = new String(data, pos[0], end - pos[0], StandardCharsets.ISO_8859_1);
        pos[0] = Math.min(end + 2, data.length);
        switch (type) {
            case '+' -> out.append(head);
            case '-' -> out.append("(error) ").append(head);
            case ':' -> out.append("(integer) ").append(head);
            case '$' -> {
                int length = Integer.parseInt(head);
                if (length < 0) {
                    out.append("(nil)");
                } else {
                    out.append('"').append(new String(data, pos[0], length, StandardCharsets.UTF_8)).append('"');
                    pos[0] += length + 2;
                }
            }
            case '*' -> {
                int count = Integer.parseInt(head);
                if (count <= 0) {
                    out.append("(empty array)");
                }
                for (int i = 0; i < count; i++) {
                    out.append(i == 0 ? "" : "\n" + indent).append(i + 1).append(") ");
                    render(data, pos, out, indent + "   ");
                }
            }
            default -> out.append("(unknown reply)");
        }
    }
}
