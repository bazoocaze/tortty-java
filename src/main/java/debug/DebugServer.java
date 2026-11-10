package debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class DebugServer {

    public interface Handler {
        Map<String, Object> state();
        void resize(int cols, int rows);
        void write(String data);
        Map<String, Object> prof();
        Map<String, Object> screen();
        /** Lines of the active tab: {@code view}, {@code history} or {@code screen}. */
        Map<String, Object> text(String where, int start, int count);
        /** A key through the same encoder as the real keyboard, answering what left. */
        Map<String, Object> keys(String spec);
        /** Debug log on/off/where, at runtime. */
        Map<String, Object> log(String value);
        void wsNew();
        void wsSelect(int index);
        void wsClose(int index);
        void wsRename(int index, String name);
        /** Paths as if dropped on the window (`B33`); the app quotes and pastes them. */
        void drop(java.util.List<String> paths);
        void quit();
    }

    private final String path;
    private final Handler handler;
    private final Gson gson = new Gson();
    private volatile boolean running = true;

    public DebugServer(String path, Handler handler) {
        this.path = path;
        this.handler = handler;
    }

    public void start() {
        Thread t = new Thread(this::run, "debug-sock");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        Path p = Paths.get(path);
        try {
            Files.deleteIfExists(p);
            ServerSocketChannel ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            ssc.bind(UnixDomainSocketAddress.of(path));
            try {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
            Log.ln("debug socket: " + path);
            while (running) {
                SocketChannel sc = ssc.accept();
                Thread clientThread = new Thread(() -> handleClient(sc), "debug-client");
                clientThread.setDaemon(true);
                clientThread.start();
            }
        } catch (Exception e) {
            System.err.println("debug socket failed: " + e);
        }
    }

    private void handleClient(SocketChannel sc) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(sc)));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(sc)))) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject req;
                try {
                    req = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    write(out, Map.of("ok", false, "err", "bad json"));
                    continue;
                }
                String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
                try {
                    Map<String, Object> resp = dispatch(cmd, req);
                    write(out, resp);
                    if ("quit".equals(cmd)) {
                        running = false;
                        break;
                    }
                } catch (Exception e) {
                    write(out, Map.of("ok", false, "err", String.valueOf(e.getMessage())));
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { sc.close(); } catch (Exception ignored) {}
        }
    }

    private Map<String, Object> dispatch(String cmd, JsonObject req) throws Exception {
        return onEdt(() -> {
            switch (cmd) {
                case "state" -> {
                    return resp(handler.state());
                }
                case "resize" -> {
                    int c = req.has("cols") ? req.get("cols").getAsInt() : 0;
                    int r = req.has("rows") ? req.get("rows").getAsInt() : 0;
                    handler.resize(c, r);
                    return Map.of("ok", true);
                }
                case "write" -> {
                    String data = req.has("data") ? req.get("data").getAsString() : "";
                    handler.write(data);
                    return Map.of("ok", true);
                }
                case "prof" -> {
                    return resp(handler.prof());
                }
                case "screen" -> {
                    return resp(handler.screen());
                }
                // `text` (`B23`): the lines of the active tab, as Rust answers.
                // `count` is clamped to 1..2000 so one request cannot ask for a
                // whole history of 100k lines.
                case "text" -> {
                    String where = req.has("where") ? req.get("where").getAsString() : "view";
                    int start = req.has("start") ? Math.max(0, req.get("start").getAsInt()) : 0;
                    int count = req.has("count") ? req.get("count").getAsInt() : 40;
                    count = Math.max(1, Math.min(2000, count));
                    return resp(handler.text(where, start, count));
                }
                // `keys` (`B23`): local shortcut first (which may eat the key),
                // then the encoder. The hex is what went to the pty, so a test
                // can assert the bytes without a screen.
                case "keys" -> {
                    if (!req.has("data")) return Map.of("ok", false, "err", "keys needs data");
                    return resp(handler.keys(req.get("data").getAsString()));
                }
                case "log" -> {
                    String value = req.has("value") ? req.get("value").getAsString() : "off";
                    return resp(handler.log(value));
                }
                case "ws_new" -> {
                    handler.wsNew();
                    return Map.of("ok", true);
                }
                case "ws_sel" -> {
                    handler.wsSelect(req.has("index") ? req.get("index").getAsInt() : -1);
                    return Map.of("ok", true);
                }
                case "ws_close" -> {
                    handler.wsClose(req.has("index") ? req.get("index").getAsInt() : -1);
                    return Map.of("ok", true);
                }
                case "ws_rename" -> {
                    handler.wsRename(req.has("index") ? req.get("index").getAsInt() : -1,
                            req.has("name") ? req.get("name").getAsString() : "");
                    return Map.of("ok", true);
                }
                case "drop" -> {
                    java.util.List<String> paths = new java.util.ArrayList<>();
                    if (req.has("paths")) {
                        for (com.google.gson.JsonElement el : req.getAsJsonArray("paths")) {
                            paths.add(el.getAsString());
                        }
                    }
                    if (req.has("data")) paths.add(req.get("data").getAsString());
                    if (paths.isEmpty()) return Map.of("ok", false, "err", "drop needs data or paths");
                    handler.drop(paths);
                    return resp(Map.of("pasted", util.ShellQuote.quoteAll(paths)));
                }
                case "quit" -> {
                    handler.quit();
                    return Map.of("ok", true);
                }
                default -> {
                    return Map.of("ok", false, "err", "unknown cmd " + cmd);
                }
            }
        });
    }

    /**
     * One response: `ok` is true unless the handler said otherwise, and the
     * payload keys are flattened beside it (the line protocol is one object).
     */
    private static Map<String, Object> resp(Map<String, Object> payload) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        Object ok = payload.get("ok");
        r.put("ok", ok == null ? true : ok);
        for (var e : payload.entrySet()) if (!"ok".equals(e.getKey())) r.put(e.getKey(), e.getValue());
        return r;
    }

    private void write(BufferedWriter out, Map<String, Object> resp) throws Exception {
        out.write(gson.toJson(resp));
        out.write("\n");
        out.flush();
    }

    private <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return c.call();
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> ex = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { ref.set(c.call()); } catch (Exception e) { ex.set(e); }
        });
        if (ex.get() != null) throw ex.get();
        return ref.get();
    }
}
