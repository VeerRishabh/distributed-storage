import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.*;

/** Dumb blob store keyed by SHA-256. Heartbeats to the coordinator every 2s. */
public class StorageNode {
    public static void main(String[] a) throws Exception {
        var env = System.getenv();
        String id = env.getOrDefault("NODE_ID", "node1");
        int port = Integer.parseInt(env.getOrDefault("PORT", "9001"));
        Path dir = Path.of(env.getOrDefault("DATA_DIR", "./data-" + id));
        String coord = env.getOrDefault("COORDINATOR_URL", "http://localhost:8080");
        String self = env.getOrDefault("ADVERTISE_URL", "http://localhost:" + port);
        Files.createDirectories(dir);

        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(Executors.newFixedThreadPool(16));
        s.createContext("/blob/", ex -> {
            try {
                if (!Util.clusterAuth(ex)) { Util.send(ex, 403, "text/plain", "forbidden".getBytes()); return; }
                String sha = ex.getRequestURI().getPath().substring("/blob/".length());
                if (!sha.matches("[0-9a-f]{64}")) { Util.send(ex, 400, "text/plain", "bad key".getBytes()); return; }
                Path f = dir.resolve(sha);
                switch (ex.getRequestMethod()) {
                    case "PUT" -> {
                        byte[] body = Util.readBody(ex);
                        if (!Util.sha256(body).equals(sha)) { Util.send(ex, 422, "text/plain", "checksum mismatch".getBytes()); return; }
                        Path tmp = Files.createTempFile(dir, "up", ".tmp");
                        Files.write(tmp, body);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        Util.send(ex, 200, "text/plain", "ok".getBytes());
                    }
                    case "GET" -> {
                        if (!Files.exists(f)) Util.send(ex, 404, "text/plain", "missing".getBytes());
                        else Util.send(ex, 200, "application/octet-stream", Files.readAllBytes(f));
                    }
                    case "DELETE" -> { Files.deleteIfExists(f); Util.send(ex, 200, "text/plain", "ok".getBytes()); }
                    default -> Util.send(ex, 405, "text/plain", "method".getBytes());
                }
            } catch (IOException e) { Util.log(id, "error: " + e); try { Util.send(ex, 500, "text/plain", "err".getBytes()); } catch (IOException ignored) {} }
        });
        s.start();

        var hb = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(false); return t; });
        hb.scheduleAtFixedRate(() -> {
            String q = "/internal/heartbeat?id=" + id + "&url=" + java.net.URLEncoder.encode(self, java.nio.charset.StandardCharsets.UTF_8);
            if (Util.call("POST", coord + q, null) == null) Util.log(id, "heartbeat failed (is the coordinator up at " + coord + "?)");
        }, 0, 2, TimeUnit.SECONDS);
        Util.log(id, "listening on :" + port + " storing in " + dir.toAbsolutePath());
    }
}
