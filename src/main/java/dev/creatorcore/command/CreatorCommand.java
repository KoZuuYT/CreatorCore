package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.ItemSource;
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
        if (!sub.equals("versao") && !sub.equals("pacote") && !sender.hasPermission(PERM)) {
            send(sender, "<red>Você não tem permissão.");
            return true;
        }
        switch (sub) {
            case "versao" -> version(sender);
            case "atualizar" -> plugin.updater().checkNow(sender);
            case "pacote" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>Apenas jogadores podem receber o pacote.");
                } else if (plugin.resourcePack().send(player)) {
                    send(sender, "<gray>Enviando o pacote de recursos…");
                } else {
                    send(sender, "<yellow>O servidor ainda não tem um pacote de recursos configurado.");
                }
            }
            case "listar" -> list(sender);
            case "recarregar" -> {
                var loaded = plugin.itemLoader().load();
                plugin.packHost().rebuildAndRefresh();
                plugin.modelMobs().refreshAll();
                send(sender, "<green>Recarregado: <white><p0></white> item(ns), <white><p1></white> bloco(s), "
                                + "<white><p2></white> mob(s) e <white><p3></white> receita(s).",
                        String.valueOf(loaded.items()), String.valueOf(loaded.blocks()), String.valueOf(loaded.mobs()),
                        String.valueOf(loaded.recipes()));
            }
            case "dar" -> give(sender, args);
            case "invocar" -> summon(sender, args);
            default -> send(sender, "<gray>Uso: <white>/cc [versao|pacote|atualizar|listar|recarregar|dar <id> [jogador] [quantidade]|invocar <id> [quantidade]]");
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
        var blocks = plugin.registry().all(CreationType.BLOCK);
        var mobs = plugin.registry().all(CreationType.MOB);
        if (items.isEmpty() && blocks.isEmpty() && mobs.isEmpty()) {
            send(sender, "<yellow>Nada carregado. Veja as pastas plugins/CreatorCore/items/, blocks/ e mobs/.");
            return;
        }
        send(sender, "<gray>Mobs (<white><p0></white>): <white><p1>", String.valueOf(mobs.size()),
                String.join(", ", mobs.stream().map(Creation::id).toList()));
        send(sender, "<gray>Itens (<white><p0></white>): <white><p1>", String.valueOf(items.size()),
                String.join(", ", items.stream().map(Creation::id).toList()));
        send(sender, "<gray>Blocos (<white><p0></white>): <white><p1>", String.valueOf(blocks.size()),
                String.join(", ", blocks.stream().map(Creation::id).toList()));
    }

    /** Invoca um mob customizado onde o jogador está olhando (ou aos seus pés). */
    private void summon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Apenas jogadores podem invocar mobs.");
            return;
        }
        if (args.length < 2) {
            send(sender, "<gray>Uso: <white>/cc invocar <id> [quantidade]");
            return;
        }
        var found = plugin.registry().get(CreationType.MOB, args[1].toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            send(sender, "<red>Mob <white><p0></white> não existe. Veja /cc listar.", args[1]);
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > 20) {
                send(sender, "<red>Quantidade inválida (use de 1 a 20).");
                return;
            }
        }
        var target = player.getTargetBlockExact(20);
        var where = target == null ? player.getLocation() : target.getLocation().add(0.5, 1, 0.5);
        var mob = (dev.creatorcore.mob.MobDefinition) found.get();
        for (int i = 0; i < amount; i++) {
            mob.spawn(plugin, where);
        }
        send(sender, "<green>Invocado <white><p0>x <p1></white>.", String.valueOf(amount), mob.id());
    }

    /** Item ou bloco (como item) com o id dado. */
    private java.util.Optional<ItemSource> findSource(String id) {
        return plugin.registry().get(CreationType.ITEM, id)
                .or(() -> plugin.registry().get(CreationType.BLOCK, id))
                .map(creation -> (ItemSource) creation);
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "<gray>Uso: <white>/cc dar <id> [jogador] [quantidade]");
            return;
        }
        var found = findSource(args[1].toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            send(sender, "<red>Item ou bloco <white><p0></white> não existe. Veja /cc listar.", args[1]);
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
        ItemSource definition = found.get();
        ItemStack stack = definition.create(plugin, amount);
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
            return admin ? List.of("versao", "pacote", "atualizar", "listar", "recarregar", "dar", "invocar")
                    : List.of("versao", "pacote");
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (admin && args[0].equalsIgnoreCase("invocar") && args.length == 2) {
            return plugin.registry().all(CreationType.MOB).stream().map(Creation::id)
                    .filter(id -> id.startsWith(typed)).toList();
        }
        if (!admin || !args[0].equalsIgnoreCase("dar")) {
            return List.of();
        }
        if (args.length == 2) {
            return java.util.stream.Stream.of(CreationType.ITEM, CreationType.BLOCK)
                    .flatMap(type -> plugin.registry().all(type).stream()).map(Creation::id)
                    .filter(id -> id.startsWith(typed)).toList();
        }
        if (args.length == 3) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }
}
