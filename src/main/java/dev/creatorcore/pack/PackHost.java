package dev.creatorcore.pack;

import com.sun.net.httpserver.HttpServer;
import dev.creatorcore.CreatorCorePlugin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Quando há modelos importados do Blockbench, monta um pack próprio (pack base da release + modelos)
 * e o serve por um HTTP embutido. Sem modelos, ou com {@code resourcepack.host.enabled: false}, fica inativo
 * e o pack da release continua valendo.
 *
 * <p>Jogadores remotos precisam alcançar {@code resourcepack.host.address}:{@code port}.
 */
public final class PackHost {

    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;

    private final CreatorCorePlugin plugin;
    private volatile byte[] bytes;
    private volatile String sha1 = "";
    private HttpServer server;
    private int serverPort = -1;

    public PackHost(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean active() {
        return bytes != null;
    }

    public String sha1() {
        return sha1;
    }

    public String url() {
        return "http://" + plugin.getConfig().getString("resourcepack.host.address", "localhost").strip() + ":"
                + plugin.getConfig().getInt("resourcepack.host.port", 25630) + "/pack.zip";
    }

    public File baseFile() {
        return new File(plugin.getDataFolder(), "pack-base.zip");
    }

    /** Recompõe o pack e, se o conteúdo mudou, reenvia a quem está online. Thread principal. */
    public void rebuildAndRefresh() {
        String before = sha1;
        rebuild();
        if (!sha1.equals(before) && active()) {
            plugin.resourcePack().refreshOnline();
        }
    }

    /** Monta o zip (base + modelos) e garante o servidor HTTP. */
    public synchronized void rebuild() {
        var extra = plugin.models().packEntries();
        if (extra.isEmpty() || !plugin.getConfig().getBoolean("resourcepack.enabled", true)
                || !plugin.getConfig().getBoolean("resourcepack.host.enabled", true)) {
            bytes = null;
            sha1 = "";
            stopServer();
            return;
        }
        try {
            Map<String, byte[]> files = new TreeMap<>();
            if (baseFile().isFile()) {
                readZip(Files.readAllBytes(baseFile().toPath()), files);
            }
            files.putIfAbsent("pack.mcmeta", ("{\"pack\":{\"description\":\"CreatorCore\",\"min_format\":[97,0],"
                    + "\"max_format\":[99,0]}}").getBytes(StandardCharsets.UTF_8));
            files.putAll(extra);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.setLevel(9);
                for (var entry : new LinkedHashMap<>(files).entrySet()) {
                    ZipEntry e = new ZipEntry(entry.getKey());
                    e.setTime(1577836800000L); // fixo: mesmo conteúdo = mesmo SHA-1
                    zip.putNextEntry(e);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            byte[] built = out.toByteArray();
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(built));
            bytes = built;
            startServer();
            plugin.getLogger().info("Resource pack próprio pronto (" + extra.size() + " arquivo(s) de modelos): "
                    + url() + " (sha1 " + sha1 + ")");
        } catch (IOException | NoSuchAlgorithmException e) {
            bytes = null;
            sha1 = "";
            plugin.getLogger().log(Level.WARNING, "Falha ao montar o resource pack próprio: " + e);
        }
    }

    private static void readZip(byte[] zip, Map<String, byte[]> into) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().contains("..")) {
                    continue;
                }
                byte[] data = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
                if (data.length > MAX_ENTRY_BYTES) {
                    throw new IOException("arquivo grande demais no pack base: " + entry.getName());
                }
                into.put(entry.getName(), data);
            }
        }
    }

    private void startServer() throws IOException {
        int port = plugin.getConfig().getInt("resourcepack.host.port", 25630);
        if (server != null && serverPort == port) {
            return;
        }
        stopServer();
        HttpServer created = HttpServer.create(new InetSocketAddress(port), 0);
        created.createContext("/pack.zip", exchange -> {
            byte[] data = bytes;
            if (data == null || !exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, data.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(data);
                }
            }
            exchange.close();
        });
        created.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CreatorCore-PackHost");
            t.setDaemon(true);
            return t;
        }));
        created.start();
        server = created;
        serverPort = port;
    }

    public synchronized void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
            serverPort = -1;
        }
    }
}
