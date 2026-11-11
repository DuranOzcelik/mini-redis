# mini-redis

A Redis-compatible server written from scratch in Java 21. It speaks the real Redis wire
protocol (RESP2), so existing Redis clients can connect to it, and it ships with a live web
dashboard.

No runtime dependencies. Only the JDK.

![Dashboard: throughput chart, keys with expiry, console, hot keys and per-command timing](docs/dashboard.png)

## Features

- RESP2 parser for array commands and inline commands, binary-safe keys and values
- `PING` `ECHO` `SET` (with `EX`, `PX`, `NX`, `XX`) `GET` `DEL` `EXISTS` `INCR` `DECR` `INCRBY`
  `DECRBY` `EXPIRE` `PEXPIRE` `TTL` `PTTL` `PERSIST` `KEYS` `DBSIZE` `FLUSHALL` `INFO`
- `HOTKEYS`: the most frequently used keys, found in fixed memory with the Space-Saving
  algorithm
- Call count and average execution time per command
- Key expiry, both lazy (on access) and active (background sweep)
- Pipelining: a batch of commands is answered with a single network write
- One virtual thread per connection
- Dashboard with throughput chart, key browser, console, hot keys and command timing

## Run

Requires JDK 21 and Maven.

```bash
mvn package
```

```bash
java -jar target/mini-redis-1.0.0.jar
```

The server listens on `127.0.0.1:6379` and the dashboard on <http://127.0.0.1:8080>.
Both ports can be changed: `java -jar target/mini-redis-1.0.0.jar 6380 8081`.

Any Redis client works:

```
$ redis-cli
127.0.0.1:6379> SET session:42 alice EX 60
OK
127.0.0.1:6379> TTL session:42
(integer) 60
127.0.0.1:6379> INCR page:views
(integer) 1
127.0.0.1:6379> HOTKEYS 2
1) "session:9f2c41"
2) (integer) 296175
3) "page:views"
4) (integer) 134112
```

## Design

```
Server      accepts connections, one virtual thread each
  Resp      decodes commands from bytes, encodes replies
  Commands  validates arguments, calls the store, writes the reply
  Store     ConcurrentHashMap of key -> (value, expiry time)
  HotKeys   Space-Saving sketch of the most used keys
Dashboard   JDK HttpServer exposing stats, keys and a console
```

## Details that were not obvious

- **Finding hot keys without counting every key.** An exact counter per key needs memory
  proportional to the number of keys, which is the thing a cache is short of. Space-Saving
  keeps a fixed number of counters (64 here). When a new key arrives and all counters are in
  use, the key with the smallest count is replaced and the newcomer inherits that count. A
  count can therefore be too high but never too low, and each entry carries the size of its
  possible error. A test pushes 11,000 distinct keys through 16 counters and still finds the
  two heavy hitters, with the true count inside the reported bounds.
- **Statistics must not become a memory leak.** Timing is kept per command name. Unknown
  names all share one bucket, otherwise a client could grow the table by sending random
  command names.
- **Atomic commands without a global lock.** Real Redis is single threaded, which makes every
  command atomic for free. This server is multi threaded, so `INCR` and `SET NX` run inside
  `ConcurrentHashMap.compute`, which locks only the affected key. A test hammers one counter
  from 8 connections and checks that no increment is lost.
- **Two kinds of expiry.** Checking the deadline on every read is not enough: a key that is
  never read again would stay in memory forever. A background task removes those.
- **Pipelining.** Replies are buffered while more input is already waiting and flushed once
  the input runs dry. Flushing after every command would cost one system call per command.
- **Binary safety.** Bulk strings are length-prefixed, so a value may contain `\r\n` or any
  other byte. Keys are stored as ISO-8859-1 strings because that charset maps every byte to
  exactly one character and back.
- **The Turkish "I" problem.** Command names are case-insensitive. With a Turkish default
  locale, `"ping".toUpperCase()` returns `"PİNG"` and the command is not recognised. The
  server uses `Locale.ROOT`, and a test runs under `tr-TR` to prove it.
- **Reply injection.** The "unknown command" error echoes the client's input. Control
  characters are stripped first, otherwise a command name containing `\r\n` could forge a
  second reply and desynchronise the client.
- **Untrusted lengths.** A client can claim a bulk string is 2 GB long. Lengths are capped
  before any memory is allocated.
- **Dashboard and CSRF.** The console endpoint requires a custom header. Browsers refuse to
  send custom headers cross-origin without a CORS preflight, so another website cannot run
  commands against the local server.

## Tests

```bash
mvn test
```

30 tests in four groups: protocol parsing (including malformed and truncated input), the
store with a fake clock (expiry is tested without sleeping), the hot key sketch and its error
bounds, and integration tests that talk to a running server over TCP.

## Limitations

- Strings only. No lists, hashes, sets or sorted sets.
- No persistence, replication, transactions, pub/sub or `AUTH`. Because there is no `AUTH`,
  the server binds to the loopback interface only.
- RESP2 only.
- Hot key tracking takes one lock per key access. A busy server would sample instead.
- Active expiry scans the whole keyspace once a second. Redis samples random keys instead,
  which scales better.

## License

MIT
