package config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.function.Consumer;

/**
 * Polls the configuration file's mtime. A valid edit is delivered to the
 * callback; any parse error keeps the configuration already in effect.
 */
public final class ConfigWatcher {

    private final Path path;
    private final Consumer<Config> onReload;
    private volatile boolean running = true;

    public ConfigWatcher(Path path, Consumer<Config> onReload) {
        this.path = path;
        this.onReload = onReload;
    }

    public void start() {
        Thread t = new Thread(this::run, "config-watch");
        t.setDaemon(true);
        t.start();
    }

    public void stop() { running = false; }

    private static FileTime mtime(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.getLastModifiedTime(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void run() {
        FileTime last = mtime(path);
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            FileTime now = mtime(path);
            if (now == null || now.equals(last)) continue;
            last = now;
            Config c = Config.load(path);
            if (!c.errors.isEmpty()) {
                for (String err : c.errors) System.err.println("tortty-java: config: " + err);
                continue;
            }
            onReload.accept(c);
        }
    }
}
