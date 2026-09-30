import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * API server + metadata + failure detector + re-replication.
 * Metadata and users persist to plain files (zero dependencies). To use SQLite/PostgreSQL, replace the
 * load()/persist() pair with JDBC calls; nothing else touches storage.
 */
public class Coordinator {
    static final int REPLICAS = Integer.parseInt(System.getenv().getOrDefault("REPLICAS", "2"));
    static final long DEAD_AFTER_MS = 6000;
    static final Path DIR = Path.of(System.getenv().getOrDefault("DATA_DIR", "./coord-data"));
    static final Pattern_ NAME = new Pattern_();
    static final ExecutorService POOL = Executors.newFixedThreadPool(32);
    static final AtomicInteger RR = new AtomicInteger();

    static class Pattern_ { boolean ok(String s) { return s.matches("[A-Za-z0-9._-]{1,100}"); } }
    static class Node { final String id; volatile String url; volatile long last; Node(String id, String url) { this.id = id; this.url = url; } boolean alive() { return System.currentTimeMillis() - last < DEAD_AFTER_MS; } }
    static class Meta { final String owner, name, sha; final long size; final Set<String> nodes = new CopyOnWriteArraySet<>();
        Meta(String o, String n, String s, long z) { owner = o; name = n; sha = s; size = z; } }

    static final Map<String, Node> nodes = new ConcurrentHashMap<>();
    static final Map<String, Meta> meta = new ConcurrentHashMap<>();       // key = owner/name
    static final Map<String, String> users = new ConcurrentHashMap<>();    // user -> salt:hash
    static final Map<String, String> tokens = new ConcurrentHashMap<>();   // token -> user (memory only)

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Files.createDirectories(DIR); load();
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.setExecutor(POOL);
        s.createContext("/register", ex -> guard(ex, () -> auth(ex, true)));
        s.createContext("/login", ex -> guard(ex, () -> auth(ex, false)));
        s.createContext("/files", ex -> guard(ex, () -> files(ex)));
        s.createContext("/status", ex -> guard(ex, () -> status(ex)));
        s.createContext("/internal/heartbeat", ex -> guard(ex, () -> heartbeat(ex)));
        s.start();
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(Coordinator::heal, 2, 2, TimeUnit.SECONDS);
        Util.log("coord", "listening on :" + port + ", replication factor " + REPLICAS);
    }

    interface Body { void run() throws Exception; }
    static void guard(HttpExchange ex, Body b) {
        try { b.run(); } catch (Exception e) {
            Util.log("coord", "error " + e);
            try { Util.json(ex, 500, "{\"error\":\"" + Util.esc(String.valueOf(e.getMessage())) + "\"}"); } catch (IOException ignored) {}
        }
    }

    // ---------- auth ----------
    static byte[] pbkdf2(String pw, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).getEncoded();
    }
    static void auth(HttpExchange ex, boolean register) throws Exception {
        var f = Util.form(Util.readBody(ex));
        String u = f.getOrDefault("user", ""), p = f.getOrDefault("pass", "");
        if (!u.matches("[A-Za-z0-9_]{3,32}") || p.length() < 8) { Util.json(ex, 400, "{\"error\":\"user must be 3-32 [A-Za-z0-9_]; pass 8+ chars\"}"); return; }
        if (register) {
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            String rec = HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(pbkdf2(p, salt));
            if (users.putIfAbsent(u, rec) != null) { Util.json(ex, 409, "{\"error\":\"user exists\"}"); return; }
            persist();
        } else {
            String rec = users.get(u);
            boolean ok = rec != null && MessageDigest_isEqual(HexFormat.of().formatHex(pbkdf2(p, HexFormat.of().parseHex(rec.split(":")[0]))), rec.split(":")[1]);
            if (!ok) { Util.json(ex, 401, "{\"error\":\"bad credentials\"}"); return; }
        }
        byte[] t = new byte[24]; new SecureRandom().nextBytes(t);
        String tok = HexFormat.of().formatHex(t); tokens.put(tok, u);
        Util.json(ex, 200, "{\"token\":\"" + tok + "\"}");
    }
    static boolean MessageDigest_isEqual(String a, String b) { return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes()); }
    static String user(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? tokens.get(h.substring(7)) : null;
    }

    // ---------- cluster ----------
    static void heartbeat(HttpExchange ex) throws IOException {
        if (!Util.clusterAuth(ex)) { Util.json(ex, 403, "{\"error\":\"bad cluster key\"}"); return; }
        var q = Util.form(Optional.ofNullable(ex.getRequestURI().getRawQuery()).orElse("").getBytes());
        Node n = nodes.computeIfAbsent(q.get("id"), k -> new Node(k, q.get("url")));
        boolean wasDead = n.last != 0 && !n.alive();
        if (n.last == 0) Util.log("coord", "node joined: " + n.id + " @ " + q.get("url"));
        if (wasDead) Util.log("coord", "node recovered: " + n.id);
        n.url = q.get("url"); n.last = System.currentTimeMillis();
        Util.json(ex, 200, "{}");
    }
    static List<Node> alive() { return nodes.values().stream().filter(Node::alive).collect(Collectors.toList()); }
    static int load(Node n) { return (int) meta.values().stream().filter(m -> m.nodes.contains(n.id)).count(); }

    static void status(HttpExchange ex) throws IOException {
        String ns = nodes.values().stream().sorted(Comparator.comparing(n -> n.id)).map(n ->
            "{\"id\":\"" + n.id + "\",\"url\":\"" + n.url + "\",\"alive\":" + n.alive() + ",\"files\":" + load(n) + "}").collect(Collectors.joining(","));
        long under = meta.values().stream().filter(m -> m.nodes.stream().filter(id -> nodes.get(id) != null && nodes.get(id).alive()).count() < REPLICAS).count();
        Util.json(ex, 200, "{\"replication_factor\":" + REPLICAS + ",\"nodes\":[" + ns + "],\"files\":" + meta.size() + ",\"under_replicated\":" + under + "}");
    }

    // ---------- files ----------
    static void files(HttpExchange ex) throws Exception {
        String u = user(ex);
        if (u == null) { Util.json(ex, 401, "{\"error\":\"missing/invalid bearer token\"}"); return; }
        String path = ex.getRequestURI().getPath();
        String name = path.length() > "/files/".length() ? path.substring("/files/".length()) : "";
        String m = ex.getRequestMethod();
        if (name.isEmpty()) {
            if (!m.equals("GET")) { Util.json(ex, 405, "{}"); return; }
            String l = meta.values().stream().filter(x -> x.owner.equals(u)).map(x -> "{\"name\":\"" + x.name + "\",\"size\":" + x.size + ",\"sha256\":\"" + x.sha + "\",\"replicas\":[" + x.nodes.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",")) + "]}").collect(Collectors.joining(","));
            Util.json(ex, 200, "[" + l + "]"); return;
        }
        if (!NAME.ok(name)) { Util.json(ex, 400, "{\"error\":\"file name must match [A-Za-z0-9._-]{1,100}\"}"); return; }
        String key = u + "/" + name;
        switch (m) {
            case "PUT" -> upload(ex, u, name, key);
            case "GET" -> download(ex, key);
            case "DELETE" -> {
                Meta x = meta.remove(key);
                if (x == null) { Util.json(ex, 404, "{\"error\":\"not found\"}"); return; }
                boolean shared = meta.values().stream().anyMatch(o -> o.sha.equals(x.sha));   // content-addressed: keep blob if another file uses it
                if (!shared) for (String id : x.nodes) { Node n = nodes.get(id); if (n != null) Util.call("DELETE", n.url + "/blob/" + x.sha, null); }
                persist(); Util.json(ex, 200, "{\"deleted\":\"" + name + "\"}");
            }
            default -> Util.json(ex, 405, "{}");
        }
    }

    static void upload(HttpExchange ex, String u, String name, String key) throws Exception {
        byte[] data;
        try { data = Util.readBody(ex); } catch (IOException e) { Util.json(ex, 413, "{\"error\":\"file too large (max 50MB)\"}"); return; }
        String sha = Util.sha256(data);
        List<Node> cands = alive(); Collections.shuffle(cands);
        cands.sort(Comparator.comparingInt(Coordinator::load));                // least-loaded first
        if (cands.isEmpty()) { Util.json(ex, 503, "{\"error\":\"no live storage nodes\"}"); return; }
        List<Future<String>> fs = new ArrayList<>();
        for (Node n : cands.subList(0, Math.min(REPLICAS, cands.size())))     // parallel replica writes
            fs.add(POOL.submit(() -> Util.call("PUT", n.url + "/blob/" + sha, data) != null ? n.id : null));
        Meta x = new Meta(u, name, sha, data.length);
        for (Future<String> f : fs) { String id = f.get(); if (id != null) x.nodes.add(id); }
        if (x.nodes.isEmpty()) { Util.json(ex, 503, "{\"error\":\"all replica writes failed\"}"); return; }
        Meta old = meta.put(key, x); persist();
        Util.log("coord", "stored " + key + " sha=" + sha.substring(0, 8) + " on " + x.nodes);
        Util.json(ex, 201, "{\"name\":\"" + name + "\",\"sha256\":\"" + sha + "\",\"size\":" + data.length + ",\"replicas\":" + x.nodes.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",", "[", "]")) + (x.nodes.size() < REPLICAS ? ",\"warning\":\"under-replicated; will heal\"" : "") + "}");
    }

    static void download(HttpExchange ex, String key) throws Exception {
        Meta x = meta.get(key);
        if (x == null) { Util.json(ex, 404, "{\"error\":\"not found\"}"); return; }
        List<String> ids = new ArrayList<>(x.nodes);
        Collections.rotate(ids, -Math.floorMod(RR.getAndIncrement(), Math.max(1, ids.size())));   // round-robin load balancing
        for (String id : ids) {
            Node n = nodes.get(id);
            if (n == null || !n.alive()) continue;                               // failover: skip dead replicas
            byte[] b = Util.call("GET", n.url + "/blob/" + x.sha, null);
            if (b == null) continue;
            if (!Util.sha256(b).equals(x.sha)) {                                 // corruption detected -> drop replica, heal later
                Util.log("coord", "CORRUPT replica of " + key + " on " + id + "; dropping it");
                x.nodes.remove(id); persist(); continue;
            }
            ex.getResponseHeaders().set("X-Served-By", id); ex.getResponseHeaders().set("X-Sha256", x.sha);
            Util.send(ex, 200, "application/octet-stream", b); return;
        }
        Util.json(ex, 503, "{\"error\":\"no healthy replica available\"}");
    }

    // ---------- self-healing ----------
    static void heal() {
        try {
            for (Meta x : meta.values()) {
                List<String> live = x.nodes.stream().filter(i -> nodes.get(i) != null && nodes.get(i).alive()).collect(Collectors.toList());
                if (live.isEmpty() || live.size() >= REPLICAS) continue;
                List<Node> targets = alive().stream().filter(n -> !x.nodes.contains(n.id)).sorted(Comparator.comparingInt(Coordinator::load)).collect(Collectors.toList());
                byte[] b = null;
                for (String s : live) { byte[] t = Util.call("GET", nodes.get(s).url + "/blob/" + x.sha, null); if (t != null && Util.sha256(t).equals(x.sha)) { b = t; break; } }
                if (b == null) continue;
                for (Node t : targets) {
                    if (x.nodes.stream().filter(i -> nodes.get(i) != null && nodes.get(i).alive()).count() >= REPLICAS) break;
                    if (Util.call("PUT", t.url + "/blob/" + x.sha, b) != null) { x.nodes.add(t.id); persist(); Util.log("coord", "re-replicated " + x.owner + "/" + x.name + " -> " + t.id); }
                }
            }
        } catch (Exception e) { Util.log("coord", "heal error " + e); }
    }

    // ---------- persistence ----------
    static synchronized void persist() {
        try {
            Files.writeString(DIR.resolve("users.db"), users.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).collect(Collectors.joining("\n")));
            Files.writeString(DIR.resolve("meta.db"), meta.values().stream().map(m -> String.join("\t", m.owner, m.name, m.sha, String.valueOf(m.size), String.join(",", m.nodes))).collect(Collectors.joining("\n")));
        } catch (IOException e) { Util.log("coord", "persist failed: " + e); }
    }
    static void load() throws IOException {
        Path u = DIR.resolve("users.db"), m = DIR.resolve("meta.db");
        if (Files.exists(u)) for (String l : Files.readAllLines(u)) { String[] p = l.split("\t"); if (p.length == 2) users.put(p[0], p[1]); }
        if (Files.exists(m)) for (String l : Files.readAllLines(m)) {
            String[] p = l.split("\t", -1); if (p.length < 5) continue;
            Meta x = new Meta(p[0], p[1], p[2], Long.parseLong(p[3]));
            for (String id : p[4].split(",")) if (!id.isEmpty()) x.nodes.add(id);
            meta.put(p[0] + "/" + p[1], x);
        }
    }
}
