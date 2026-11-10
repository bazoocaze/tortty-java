package config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.function.Consumer;

/**
 * Polls the configuration file's mtime and delivers what the file says, valid
 * or not: keeping the configuration already in effect is the caller's decision,
 * and so is saying so — a watcher that swallows an error leaves the user with a
 * silent editor and no reason for the old settings (`B38`).
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
            onReload.accept(Config.load(path));
        }
    }
}
