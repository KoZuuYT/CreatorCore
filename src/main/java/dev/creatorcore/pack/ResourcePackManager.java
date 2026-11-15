package dev.creatorcore.pack;

import dev.creatorcore.CreatorCorePlugin;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Envia o resource pack aos jogadores. O link vem do {@code config.yml} (url + sha1 manuais, que têm
 * prioridade) ou é descoberto pela release mais recente do GitHub (ver {@link PackResolver}).
 * Por padrão o pacote é opcional: quem recusar joga com os itens em visual vanilla.
 */
public final class ResourcePackManager implements Listener {

    private final CreatorCorePlugin plugin;
    private final PackResolver resolver;

    public ResourcePackManager(CreatorCorePlugin plugin) {
        this.plugin = plugin;
        this.resolver = new PackResolver(plugin);
    }

    public void start() {
        // Com link manual não há o que descobrir na release.
        if (plugin.getConfig().getBoolean("resourcepack.enabled", true) && !manual()) {
            resolver.start();
        }
    }

    // ── Link efetivo ────────────────────────────────────────────

    private boolean manual() {
        return !plugin.getConfig().getString("resourcepack.url", "").isBlank();
    }

    public String url() {
        if (manual()) {
            return plugin.getConfig().getString("resourcepack.url", "").strip();
        }
        return plugin.packHost().active() ? plugin.packHost().url() : resolver.url();
    }

    public String sha1() {
        if (manual()) {
            return plugin.getConfig().getString("resourcepack.sha1", "").strip().toLowerCase(Locale.ROOT);
        }
        return plugin.packHost().active() ? plugin.packHost().sha1() : resolver.sha1();
    }

    public boolean isConfigured() {
        return plugin.getConfig().getBoolean("resourcepack.enabled", true) && !url().isEmpty();
    }

    /** Mesmo conteúdo = mesmo id (o cliente não baixa de novo); conteúdo novo = id novo. */
    private UUID packId() {
        String seed = sha1().isEmpty() ? "url:" + url() : "sha1:" + sha1();
        return UUID.nameUUIDFromBytes(("creatorcore:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    // ── Envio ───────────────────────────────────────────────────

    /** @return {@code false} se não há pacote configurado ou o link é inválido. */
    public boolean send(Player player) {
        if (!isConfigured()) {
            return false;
        }
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId(), URI.create(url()), sha1());
            Component prompt = MiniMessage.miniMessage().deserialize("<gray>Texturas dos itens do CreatorCore.");
            player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(plugin.getConfig().getBoolean("resourcepack.required", false))
                    .replace(true)
                    .prompt(prompt)
                    .build());
            return true;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("resourcepack.url/sha1 inválidos no config.yml: " + e.getMessage());
            return false;
        }
    }

    /** O pacote mudou (nova release): reenvia a quem está online. */
    public void refreshOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("resourcepack.send-on-join", true)) {
            return;
        }
        Player player = event.getPlayer();
        // Pequeno atraso: o cliente ainda está terminando de entrar no mundo.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!isConfigured() || !packId().equals(event.getID())) {
            return;
        }
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL ->
                    plugin.getLogger().warning("Resource pack falhou para " + event.getPlayer().getName()
                            + ": " + event.getStatus());
            default -> {
            }
        }
    }
}
