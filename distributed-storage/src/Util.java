import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Shared helpers: hashing, HTTP plumbing, tiny logger. */
final class Util {
    static final String CLUSTER_KEY = System.getenv().getOrDefault("CLUSTER_KEY", "dev-secret");
    static final long MAX_BODY = 50L * 1024 * 1024;
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes((int) MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new IOException("body too large");
            return b;
        }
    }

    static void send(HttpExchange ex, int code, String ctype, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        ex.close();
    }
    static void json(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static Map<String, String> form(byte[] body) {
        Map<String, String> m = new HashMap<>();
        for (String p : new String(body, StandardCharsets.UTF_8).split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) m.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Internal node<->coordinator call. Returns null on any failure (caller treats node as unhealthy). */
    static byte[] call(String method, String url, byte[] body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("X-Cluster-Key", CLUSTER_KEY);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() / 100 == 2 ? r.body() : null;
        } catch (Exception e) { return null; }
    }

    static boolean clusterAuth(HttpExchange ex) {
        return CLUSTER_KEY.equals(ex.getRequestHeaders().getFirst("X-Cluster-Key"));
    }

    static void log(String who, String msg) { System.out.println(java.time.LocalTime.now().withNano(0) + " [" + who + "] " + msg); }
}
