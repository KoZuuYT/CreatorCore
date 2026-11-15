package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Encaminha cliques às {@link Screen}s e captura respostas digitadas no chat (perguntas dos editores). */
public final class ScreenListener implements Listener {

    static final String PERM = "creatorcore.admin";

    /** Pergunta pendente: recebe o texto digitado e devolve uma mensagem de erro, ou {@code null} se aceitou. */
    private record Prompt(Function<String, String> handler, Runnable onDone) {
    }

    private final CreatorCorePlugin plugin;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    public ScreenListener(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Abre a tela no próximo tick (seguro dentro de eventos de clique). */
    public void go(Player player, Screen screen) {
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openInventory(screen.getInventory()));
    }

    /**
     * Fecha o inventário e pergunta algo no chat. Enquanto a resposta não for aceita a pergunta continua
     * valendo; "cancelar" desiste.
     *
     * @param handler recebe a resposta; devolve o erro a mostrar, ou {@code null} se aceitou
     * @param onDone  chamado depois de aceitar ou cancelar (normalmente reabre a tela)
     */
    public void ask(Player player, String instructions, Function<String, String> handler, Runnable onDone) {
        prompts.put(player.getUniqueId(), new Prompt(handler, onDone));
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> " + instructions
                + "\n<dark_gray>Digite a resposta no chat. Para desistir, digite <white>cancelar</white>."));
    }

    public boolean isAsking(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.get(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (prompts.get(player.getUniqueId()) != prompt) {
                return;
            }
            if (text.equalsIgnoreCase("cancelar")) {
                prompts.remove(player.getUniqueId());
                player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <gray>Cancelado."));
                prompt.onDone().run();
                return;
            }
            String error;
            try {
                error = prompt.handler().apply(text);
            } catch (RuntimeException e) {
                error = e.getMessage() == null ? "valor inválido" : e.getMessage();
            }
            if (error != null) {
                player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>" + Screen.esc(error)
                        + "</red> <gray>Tente de novo ou digite <white>cancelar</white>."));
                return;
            }
            prompts.remove(player.getUniqueId());
            prompt.onDone().run();
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Screen screen)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission(PERM)) {
            return;
        }
        boolean top = event.getClickedInventory() == event.getInventory();
        screen.onClick(player, event, top ? event.getSlot() : -1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Screen) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Screen screen && event.getPlayer() instanceof Player player) {
            screen.onClose(player);
        }
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
