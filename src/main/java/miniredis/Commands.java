package miniredis;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Maps a parsed command to a Store operation and writes the RESP reply. */
final class Commands {
    private static final String NOT_INTEGER = "ERR value is not an integer or out of range";
    private static final String SYNTAX = "ERR syntax error";

    private final Store store;
    private final Stats stats;

    Commands(Store store, Stats stats) {
        this.store = store;
        this.stats = stats;
    }

    void execute(List<byte[]> args, OutputStream out) throws IOException {
        // Locale.ROOT matters: on a Turkish system "ping".toUpperCase() is "PİNG", not "PING".
        String name = text(args.get(0)).toUpperCase(Locale.ROOT);
        int argc = args.size() - 1;
        stats.commands.increment();
        switch (name) {
            case "PING" -> {
                if (argc == 0) {
                    Resp.simple(out, "PONG");
                } else if (argc == 1) {
                    Resp.bulk(out, args.get(1));
                } else {
                    wrongArity(out, name);
                }
            }
            case "ECHO" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                } else {
                    Resp.bulk(out, args.get(1));
                }
            }
            case "GET" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                } else {
                    Resp.bulk(out, store.get(key(args, 1)));
                }
            }
            case "SET" -> set(args, out);
            case "DEL", "EXISTS" -> {
                if (argc < 1) {
                    wrongArity(out, name);
                    return;
                }
                long count = 0;
                for (int i = 1; i <= argc; i++) {
                    if (name.equals("DEL") ? store.delete(key(args, i)) : store.exists(key(args, i))) {
                        count++;
                    }
                }
                Resp.integer(out, count);
            }
            case "INCR", "DECR" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                } else {
                    increment(out, key(args, 1), name.equals("INCR") ? 1 : -1);
                }
            }
            case "INCRBY", "DECRBY" -> {
                if (argc != 2) {
                    wrongArity(out, name);
                    return;
                }
                Long delta = integer(args.get(2));
                if (delta == null || (name.equals("DECRBY") && delta == Long.MIN_VALUE)) {
                    Resp.error(out, NOT_INTEGER);
                } else {
                    increment(out, key(args, 1), name.equals("INCRBY") ? delta : -delta);
                }
            }
            case "EXPIRE", "PEXPIRE" -> {
                if (argc != 2) {
                    wrongArity(out, name);
                    return;
                }
                Long ttl = integer(args.get(2));
                if (ttl == null) {
                    Resp.error(out, NOT_INTEGER);
                } else if (name.equals("EXPIRE") && Math.abs(ttl) > Long.MAX_VALUE / 1000) {
                    Resp.error(out, "ERR invalid expire time in 'expire' command");
                } else {
                    Resp.integer(out, store.expire(key(args, 1), name.equals("EXPIRE") ? ttl * 1000 : ttl) ? 1 : 0);
                }
            }
            case "TTL", "PTTL" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                    return;
                }
                long millis = store.ttlMillis(key(args, 1));
                Resp.integer(out, millis < 0 || name.equals("PTTL") ? millis : (millis + 500) / 1000);
            }
            case "PERSIST" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                } else {
                    Resp.integer(out, store.persist(key(args, 1)) ? 1 : 0);
                }
            }
            case "KEYS" -> {
                if (argc != 1) {
                    wrongArity(out, name);
                    return;
                }
                List<String> keys = store.keys(key(args, 1));
                Resp.arrayHeader(out, keys.size());
                for (String key : keys) {
                    Resp.bulk(out, key.getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            case "DBSIZE" -> Resp.integer(out, store.size());
            case "FLUSHALL", "FLUSHDB" -> {
                store.clear();
                Resp.simple(out, "OK");
            }
            case "INFO" -> Resp.bulk(out, info().getBytes(StandardCharsets.US_ASCII));
            // Clients such as redis-cli and redis-benchmark send these on connect.
            case "COMMAND", "CONFIG" -> Resp.arrayHeader(out, 0);
            case "SELECT", "CLIENT" -> Resp.simple(out, "OK");
            default -> Resp.error(out, "ERR unknown command '" + printable(text(args.get(0))) + "'");
        }
    }

    private void set(List<byte[]> args, OutputStream out) throws IOException {
        if (args.size() < 3) {
            wrongArity(out, "SET");
            return;
        }
        long ttl = 0;
        boolean nx = false;
        boolean xx = false;
        for (int i = 3; i < args.size(); i++) {
            String option = text(args.get(i)).toUpperCase(Locale.ROOT);
            switch (option) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "EX", "PX" -> {
                    if (++i >= args.size() || ttl != 0) {
                        Resp.error(out, SYNTAX);
                        return;
                    }
                    Long value = integer(args.get(i));
                    if (value == null) {
                        Resp.error(out, NOT_INTEGER);
                        return;
                    }
                    if (value <= 0 || (option.equals("EX") && value > Long.MAX_VALUE / 1000)) {
                        Resp.error(out, "ERR invalid expire time in 'set' command");
                        return;
                    }
                    ttl = option.equals("EX") ? value * 1000 : value;
                }
                default -> {
                    Resp.error(out, SYNTAX);
                    return;
                }
            }
        }
        if (nx && xx) {
            Resp.error(out, SYNTAX);
        } else if (store.set(key(args, 1), args.get(2), ttl, nx, xx)) {
            Resp.simple(out, "OK");
        } else {
            Resp.bulk(out, null);
        }
    }

    private void increment(OutputStream out, String key, long delta) throws IOException {
        try {
            Resp.integer(out, store.incrementBy(key, delta));
        } catch (NumberFormatException e) {
            Resp.error(out, NOT_INTEGER);
        } catch (ArithmeticException e) {
            Resp.error(out, "ERR increment or decrement would overflow");
        }
    }

    private String info() {
        return "# Server\r\nredis_version:7.0.0\r\nserver_name:mini-redis\r\n"
                + "uptime_in_seconds:" + (System.currentTimeMillis() - stats.startedAt) / 1000 + "\r\n"
                + "# Clients\r\nconnected_clients:" + stats.clients.get() + "\r\n"
                + "# Stats\r\ntotal_commands_processed:" + stats.commands.sum() + "\r\n"
                + "# Keyspace\r\ndb0:keys=" + store.size() + "\r\n";
    }

    private static void wrongArity(OutputStream out, String name) throws IOException {
        Resp.error(out, "ERR wrong number of arguments for '" + name.toLowerCase(Locale.ROOT) + "' command");
    }

    /** ISO-8859-1 maps every byte to one char and back, so keys stay binary-safe as Strings. */
    private static String key(List<byte[]> args, int index) {
        return text(args.get(index));
    }

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    private static Long integer(byte[] raw) {
        try {
            return Store.parseInteger(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** An error reply is a single line, so client input echoed into it must not contain CR or LF. */
    private static String printable(String input) {
        String shortened = input.length() > 64 ? input.substring(0, 64) : input;
        return shortened.replaceAll("[^\\x20-\\x7e]", "?");
    }
}
