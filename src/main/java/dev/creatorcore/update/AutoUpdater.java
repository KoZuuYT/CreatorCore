package dev.creatorcore.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.creatorcore.CreatorCorePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sincronização automática com o GitHub: consulta a release mais recente e, se for mais nova
 * que a versão em execução, baixa o jar para {@code plugins/update/}. O próprio servidor troca
 * o jar na próxima inicialização (mecanismo padrão da pasta update). Rede só fora da thread principal.
 */
public final class AutoUpdater implements Listener, TabExecutor {

    private static final String PERM = "creatorcore.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CreatorCorePlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicBoolean checking = new AtomicBoolean();
    /** Versão já baixada, aguardando reinício. */
    private volatile String pendingVersion;

    public AutoUpdater(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("updater.enabled", true)) {
            plugin.getLogger().info("Atualização automática desativada no config.yml.");
            return;
        }
        long interval = Math.max(5, plugin.getConfig().getLong("updater.check-interval-minutes", 30)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null), 20L * 15, interval);
    }

    public String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // ── Checagem ────────────────────────────────────────────────

    /** Bloqueante: chame fora da thread principal. {@code feedback} pode ser {@code null}. */
    private void check(CommandSender feedback) {
        if (!checking.compareAndSet(false, true)) {
            tell(feedback, "<yellow>Já existe uma checagem em andamento.");
            return;
        }
        try {
            String repository = plugin.getConfig().getString("updater.repository", "").strip();
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
                tell(feedback, "<red>updater.repository inválido no config.yml (use dono/repositorio).");
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CreatorCore-Updater")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                tell(feedback, "<yellow>O repositório ainda não tem nenhuma release publicada.");
                return;
            }
            if (response.statusCode() != 200) {
                tell(feedback, "<red>O GitHub respondeu com o código " + response.statusCode() + ".");
                return;
            }
            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            String latest = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
            if (compare(latest, currentVersion()) <= 0) {
                tell(feedback, "<green>Já está na versão mais recente (<white>" + currentVersion() + "</white>).");
                return;
            }
            if (latest.equals(pendingVersion)) {
                tell(feedback, "<yellow>A versão <white>" + latest + "</white> já foi baixada e será aplicada no próximo reinício.");
                return;
            }
            JsonObject asset = findJar(release);
            if (asset == null) {
                tell(feedback, "<red>A release " + latest + " não contém um arquivo .jar.");
                return;
            }
            String url = asset.get("browser_download_url").getAsString();
            if (!url.startsWith("https://github.com/" + repository + "/releases/download/")) {
                tell(feedback, "<red>URL de download inesperada; atualização ignorada por segurança.");
                return;
            }
            tell(feedback, "<gray>Baixando a versão <white>" + latest + "</white>…");
            download(url, asset);
            pendingVersion = latest;
            plugin.getLogger().info("CreatorCore " + latest + " baixado para plugins/update/. Reinicie o servidor para aplicar.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    notifyAdmin(online);
                }
                restartIfEmpty();
            });
            tell(feedback, "<green>Versão <white>" + latest + "</white> baixada. Será aplicada no próximo reinício.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Falha ao checar atualizações: " + e);
            tell(feedback, "<red>Falha ao checar atualizações: <gray><err>", String.valueOf(e.getMessage()));
        } finally {
            checking.set(false);
        }
    }

    private static JsonObject findJar(JsonObject release) {
        if (!release.has("assets")) {
            return null;
        }
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            if (asset.get("name").getAsString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return asset;
            }
        }
        return null;
    }

    /** Baixa, valida (SHA-256 da release e conteúdo do jar) e só então move para a pasta update. */
    private void download(String url, JsonObject asset) throws Exception {
        Path updateDir = Bukkit.getUpdateFolderFile().toPath();
        Files.createDirectories(updateDir);
        String jarName = plugin.jarFile().getName();
        Path partial = updateDir.resolve(jarName + ".part");
        Path target = updateDir.resolve(jarName);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "CreatorCore-Updater")
                .timeout(Duration.ofMinutes(2))
                .GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(partial));
        try {
            if (response.statusCode() != 200) {
                throw new IOException("download respondeu com o código " + response.statusCode());
            }
            JsonElement digest = asset.get("digest");
            if (digest != null && !digest.isJsonNull() && digest.getAsString().startsWith("sha256:")) {
                String expected = digest.getAsString().substring("sha256:".length());
                if (!expected.equalsIgnoreCase(sha256(partial))) {
                    throw new IOException("o SHA-256 do arquivo baixado não confere com o da release");
                }
            }
            try (JarFile jar = new JarFile(partial.toFile())) {
                if (jar.getEntry("plugin.yml") == null) {
                    throw new IOException("o arquivo baixado não é um plugin válido");
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Compara versões numéricas separadas por ponto (ex.: 0.10.0 > 0.9.2). */
    static int compare(String a, String b) {
        String[] left = a.split("[.\\-+]");
        String[] right = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? number(left[i]) : 0;
            int r = i < right.length ? number(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Avisos e reinício ───────────────────────────────────────

    private static void send(CommandSender to, String mini, String err) {
        var resolver = Placeholder.unparsed("err", err == null ? "" : err);
        to.sendMessage(MM.deserialize("<gold>[CreatorCore]</gold> " + mini, resolver));
    }

    private void tell(CommandSender feedback, String message) {
        tell(feedback, message, null);
    }

    private void tell(CommandSender feedback, String message, String err) {
        if (feedback != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> send(feedback, message, err));
        }
    }

    private void notifyAdmin(Player player) {
        if (pendingVersion != null && player.hasPermission(PERM)) {
            send(player, "<yellow>Versão <white>" + pendingVersion + "</white> baixada. <gray>Reinicie o servidor para aplicar.", null);
        }
    }

    private void restartIfEmpty() {
        if (pendingVersion != null && plugin.getConfig().getBoolean("updater.restart-when-empty", false)
                && Bukkit.getOnlinePlayers().isEmpty()) {
            plugin.getLogger().info("Servidor vazio: desligando para aplicar o CreatorCore " + pendingVersion + ".");
            Bukkit.shutdown();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        notifyAdmin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (pendingVersion != null) {
            Bukkit.getScheduler().runTask(plugin, this::restartIfEmpty);
        }
    }

    // ── /creatorcore ────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("atualizar")) {
            if (!sender.hasPermission(PERM)) {
                send(sender, "<red>Sem permissão.", null);
                return true;
            }
            send(sender, "<gray>Checando a release mais recente no GitHub…", null);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> check(sender));
            return true;
        }
        send(sender, "<gray>Versão em execução: <white>" + currentVersion() + "</white>"
                + (pendingVersion == null ? "" : " <dark_gray>| <yellow>aguardando reinício: <white>" + pendingVersion), null);
        if (sender.hasPermission(PERM)) {
            send(sender, "<gray>Use <white>/creatorcore atualizar</white> para checar o GitHub agora.", null);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return sender.hasPermission(PERM) ? List.of("versao", "atualizar") : List.of("versao");
    }
}
