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
                    Map<String, Object> m = new java.util.LinkedHashMap<>(handler.state());
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("ok", true);
                    for (var e : m.entrySet()) if (!"ok".equals(e.getKey())) r.put(e.getKey(), e.getValue());
                    return r;
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
                    Map<String, Object> m = new java.util.LinkedHashMap<>(handler.prof());
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("ok", true);
                    for (var e : m.entrySet()) if (!"ok".equals(e.getKey())) r.put(e.getKey(), e.getValue());
                    return r;
                }
                case "screen" -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(handler.screen());
                    Map<String, Object> r = new java.util.LinkedHashMap<>();
                    r.put("ok", true);
                    for (var e : m.entrySet()) if (!"ok".equals(e.getKey())) r.put(e.getKey(), e.getValue());
                    return r;
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
