package miniredis;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespTest {
    private static InputStream wire(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static List<String> strings(List<byte[]> args) {
        return args.stream().map(a -> new String(a, StandardCharsets.ISO_8859_1)).toList();
    }

    @Test
    void readsArrayCommand() throws IOException {
        assertEquals(List.of("SET", "k", "v"),
                strings(Resp.readCommand(wire("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n"))));
    }

    @Test
    void readsInlineCommand() throws IOException {
        assertEquals(List.of("PING"), strings(Resp.readCommand(wire("PING\r\n"))));
        assertEquals(List.of("SET", "a", "b"), strings(Resp.readCommand(wire("  SET  a b \r\n"))));
    }

    @Test
    void readsPipelinedCommandsOneByOne() throws IOException {
        InputStream in = wire("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n");
        assertEquals(List.of("PING"), strings(Resp.readCommand(in)));
        assertEquals(List.of("ECHO", "hi"), strings(Resp.readCommand(in)));
        assertNull(Resp.readCommand(in));
    }

    @Test
    void bulkStringsAreBinarySafe() throws IOException {
        List<byte[]> args = Resp.readCommand(wire("*2\r\n$4\r\nECHO\r\n$6\r\na\r\n\0\r\n\r\n"));
        assertArrayEquals(new byte[]{'a', '\r', '\n', 0, '\r', '\n'}, args.get(1));
    }

    @Test
    void returnsNullWhenClientDisconnectsMidCommand() throws IOException {
        assertNull(Resp.readCommand(wire("")));
        assertNull(Resp.readCommand(wire("*2\r\n$4\r\nEC")));
        assertNull(Resp.readCommand(wire("*2\r\n$4\r\nECHO\r\n")));
    }

    @Test
    void rejectsMalformedInput() {
        String[] bad = {
                "*1\r\n:5\r\n",                    // argument is not a bulk string
                "*1\r\n$abc\r\n",                  // length is not a number
                "*1\r\n$-5\r\n",                   // negative length
                "*1\r\n$3\r\nabcXX",               // missing CRLF after payload
                "*1\r\n$999999999\r\n",            // bulk larger than the limit
                "*99999999\r\n",                   // too many arguments
        };
        for (String input : bad) {
            assertThrows(Resp.ProtocolException.class, () -> Resp.readCommand(wire(input)), input);
        }
    }

    @Test
    void rendersRepliesLikeRedisCli() {
        assertEquals("PONG", render("+PONG\r\n"));
        assertEquals("(error) ERR nope", render("-ERR nope\r\n"));
        assertEquals("(integer) 42", render(":42\r\n"));
        assertEquals("(nil)", render("$-1\r\n"));
        assertEquals("\"hi\"", render("$2\r\nhi\r\n"));
        assertEquals("(empty array)", render("*0\r\n"));
        assertEquals("1) \"a\"\n2) \"b\"", render("*2\r\n$1\r\na\r\n$1\r\nb\r\n"));
    }

    private static String render(String reply) {
        return Resp.render(reply.getBytes(StandardCharsets.ISO_8859_1));
    }
}
