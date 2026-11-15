package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.ItemDefinition;
import dev.creatorcore.registry.Creation;
import dev.creatorcore.registry.CreationType;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /creatorcore} (alias {@code /cc}): versão, atualização e itens customizados. */
public final class CreatorCommand implements TabExecutor {

    private static final String PERM = "creatorcore.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CreatorCorePlugin plugin;

    public CreatorCommand(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Envia uma mensagem MiniMessage com prefixo; valores de jogador entram só via placeholders {@code <p0>…}. */
    private static void send(CommandSender to, String mini, String... values) {
        TagResolver.Builder resolvers = TagResolver.builder();
        for (int i = 0; i < values.length; i++) {
            resolvers.resolver(Placeholder.unparsed("p" + i, values[i]));
        }
        to.sendMessage(MM.deserialize("<gold>[CreatorCore]</gold> " + mini, resolvers.build()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "versao" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("versao") && !sender.hasPermission(PERM)) {
            send(sender, "<red>Você não tem permissão.");
            return true;
        }
        switch (sub) {
            case "versao" -> version(sender);
            case "atualizar" -> plugin.updater().checkNow(sender);
            case "listar" -> list(sender);
            case "recarregar" -> {
                int loaded = plugin.itemLoader().load();
                send(sender, "<green>Itens recarregados: <white><p0></white>.", String.valueOf(loaded));
            }
            case "dar" -> give(sender, args);
            default -> send(sender, "<gray>Uso: <white>/cc [versao|atualizar|listar|recarregar|dar <id> [jogador] [quantidade]]");
        }
        return true;
    }

    private void version(CommandSender sender) {
        String pending = plugin.updater().pendingVersion();
        send(sender, "<gray>Versão em execução: <white><p0></white>"
                        + (pending == null ? "" : " <dark_gray>| <yellow>aguardando reinício: <white><p1>"),
                plugin.updater().currentVersion(), String.valueOf(pending));
    }

    private void list(CommandSender sender) {
        var items = plugin.registry().all(CreationType.ITEM);
        if (items.isEmpty()) {
            send(sender, "<yellow>Nenhum item carregado. Veja a pasta plugins/CreatorCore/items/.");
            return;
        }
        send(sender, "<gray>Itens (<white><p0></white>): <white><p1>", String.valueOf(items.size()),
                String.join(", ", items.stream().map(Creation::id).toList()));
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "<gray>Uso: <white>/cc dar <id> [jogador] [quantidade]");
            return;
        }
        var found = plugin.registry().get(CreationType.ITEM, args[1].toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            send(sender, "<red>Item <white><p0></white> não existe. Veja /cc listar.", args[1]);
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                send(sender, "<red>Jogador <white><p0></white> não está online.", args[2]);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, "<red>No console, informe o jogador: /cc dar <id> <jogador> [quantidade]");
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > 64 * 36) {
                send(sender, "<red>Quantidade inválida (use de 1 a 2304).");
                return;
            }
        }
        ItemDefinition definition = (ItemDefinition) found.get();
        ItemStack stack = definition.create(plugin.idKey(), amount);
        int total = stack.getAmount();
        int maxStack = stack.getMaxStackSize();
        // Divide em pilhas válidas; o que não couber no inventário cai aos pés do jogador.
        int remaining = total;
        while (remaining > 0) {
            ItemStack part = stack.clone();
            part.setAmount(Math.min(maxStack, remaining));
            remaining -= part.getAmount();
            target.getInventory().addItem(part).values()
                    .forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
        }
        send(sender, "<green>Entregue <white><p0>x <p1></white> para <white><p2></white>.",
                String.valueOf(total), definition.id(), target.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission(PERM);
        if (args.length == 1) {
            return admin ? List.of("versao", "atualizar", "listar", "recarregar", "dar") : List.of("versao");
        }
        if (!admin || !args[0].equalsIgnoreCase("dar")) {
            return List.of();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return plugin.registry().all(CreationType.ITEM).stream().map(Creation::id)
                    .filter(id -> id.startsWith(typed)).toList();
        }
        if (args.length == 3) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }
}
