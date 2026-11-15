package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.ItemSource;
import dev.creatorcore.mob.MobDefinition;
import dev.creatorcore.profile.ProfileManager;
import dev.creatorcore.registry.Creation;
import dev.creatorcore.registry.CreationType;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Menu principal do {@code /cc}: tudo o que o plugin faz, os perfis e as listas de criações do perfil. */
public final class HubGui {

    private static final int PAGE = 45;

    private final CreatorCorePlugin plugin;

    public HubGui(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static void say(CommandSender to, String mini) {
        to.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> " + mini));
    }

    // ── Ações compartilhadas com os comandos ──

    /** Lista as funcionalidades no chat. */
    public void sendHelp(CommandSender to) {
        say(to, "<yellow>O que o CreatorCore faz:");
        for (String line : new String[] {
                "<white>Itens</white> <gray>— nome, lore, atributos, encantamentos, durabilidade, comida, armadura com textura, habilidades, receitas e cozimento.",
                "<white>Blocos</white> <gray>— modelo próprio, dureza, ferramenta, drops com chance e Fortuna, sons, partículas, XP e comandos.",
                "<white>Mobs</white> <gray>— atributos, equipamento, drops, modelo 3D animado, spawn natural, passivo, domável, ataque à distância, habilidades, chefe com barra.",
                "<white>Perfis</white> <gray>— cada criação fica num perfil; exporte como código e importe em outro servidor.",
                "<white>Pacote</white> <gray>— o resource pack é gerado e enviado sozinho; o plugin se atualiza pelo GitHub.",
                "<gray>Comandos: <white>/cc</white> (menu), <white>/cc menu</white> (catálogo), <white>/cc perfil</white>, <white>/cc ajuda</white>."}) {
            to.sendMessage(Screen.MM.deserialize(line));
        }
    }

    /** Exporta o perfil: grava o código em arquivo e mostra um botão para copiar. */
    public void exportProfile(CommandSender to, String name) {
        if (!plugin.profiles().exists(name)) {
            say(to, "<red>Perfil '" + Screen.esc(name) + "' não existe.");
            return;
        }
        try {
            var exported = plugin.profiles().export(name);
            File file = new File(plugin.profiles().exportDir(), name + ".cccode");
            Files.writeString(file.toPath(), exported.code(), StandardCharsets.UTF_8);
            Component copy = Component.text("[Copiar código]", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.copyToClipboard(exported.code()))
                    .hoverEvent(HoverEvent.showText(Component.text("Copia o código para a área de transferência")));
            to.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <green>Perfil <white>" + name
                    + "</white> exportado</green> <gray>(" + exported.code().length() + " caracteres). ").append(copy));
            say(to, "<gray>Também salvo em <white>plugins/CreatorCore/perfis/_exportados/" + name + ".cccode</white>.");
            say(to, "<gray>No outro servidor: <white>/cc perfil importar <código></white> ou coloque o arquivo na pasta e use "
                    + "<white>/cc perfil importar-arquivo " + name + "</white>.");
            if (!exported.needed().isEmpty()) {
                say(to, "<yellow>Leve também estes arquivos (modelos e texturas não vão no código): <white>"
                        + Screen.esc(String.join(", ", exported.needed())));
            }
        } catch (IOException e) {
            say(to, "<red>Não consegui exportar: " + Screen.esc(String.valueOf(e.getMessage())));
        }
    }

    /** Importa um código como um perfil novo e recarrega tudo. */
    public void importCode(CommandSender to, String code, String name) {
        try {
            var result = plugin.profiles().importCode(code, name);
            var loaded = plugin.reloadAll();
            say(to, "<green>Perfil <white>" + result.name() + "</white> importado: <white>" + result.items() + "</white> item(ns), <white>"
                    + result.blocks() + "</white> bloco(s), <white>" + result.mobs() + "</white> mob(s).");
            say(to, "<gray>Use <white>/cc perfil usar " + result.name() + "</white> para editá-lo. Carregado agora: "
                    + loaded.items() + " itens, " + loaded.blocks() + " blocos, " + loaded.mobs() + " mobs.");
            if (!result.duplicates().isEmpty()) {
                say(to, "<yellow>Estes ids já existem neste servidor e foram ignorados na importação: <white>"
                        + Screen.esc(String.join(", ", result.duplicates())));
            }
            if (!result.missingFiles().isEmpty()) {
                say(to, "<yellow>Faltam arquivos neste servidor (criações que os usam ficam com o visual base): <white>"
                        + Screen.esc(String.join(", ", result.missingFiles())));
            }
        } catch (IOException e) {
            say(to, "<red>Importação recusada: " + Screen.esc(String.valueOf(e.getMessage())));
        }
    }

    /** Importa um arquivo {@code .cccode} de perfis/_exportados. */
    public void importFile(CommandSender to, String fileName, String name) {
        String clean = fileName.replaceAll("[^A-Za-z0-9_.-]", "");
        File file = new File(plugin.profiles().exportDir(), clean.endsWith(".cccode") ? clean : clean + ".cccode");
        if (!file.isFile()) {
            say(to, "<red>Arquivo não encontrado em perfis/_exportados: <white>" + Screen.esc(file.getName()));
            return;
        }
        try {
            importCode(to, Files.readString(file.toPath(), StandardCharsets.UTF_8), name);
        } catch (IOException e) {
            say(to, "<red>Não consegui ler o arquivo: " + Screen.esc(String.valueOf(e.getMessage())));
        }
    }

    // ── Menu principal ──

    public void openHub(Player player) {
        plugin.ui().go(player, new HubScreen());
    }

    public void openMine(Player player, CreationType type, int page) {
        plugin.ui().go(player, new MineScreen(type, page));
    }

    public void openProfiles(Player player) {
        plugin.ui().go(player, new ProfilesScreen());
    }

    private final class HubScreen extends Screen {
        HubScreen() {
            super(HubGui.this.plugin, 4, "<dark_gray>CreatorCore <gold>" + HubGui.this.plugin.getPluginMeta().getVersion());
            String profile = plugin.profiles().active();
            inventory.setItem(4, icon(Material.NETHER_STAR, "<gold>CreatorCore", "<gray>Perfil atual: <white>" + profile,
                    "<dark_gray>Tudo o que você cria vai para o perfil atual."));
            inventory.setItem(10, icon(Material.DIAMOND_SWORD, "<aqua>Criar item", "<gray>Nome, lore, atributos, encantamentos,",
                    "<gray>comida, armadura, habilidades, receita...", "<yellow>Clique para começar"));
            inventory.setItem(11, icon(Material.NOTE_BLOCK, "<aqua>Criar bloco", "<gray>Modelo, dureza, ferramenta, drops,",
                    "<gray>sons, partículas, comandos...", "<yellow>Clique para começar"));
            inventory.setItem(12, icon(Material.ZOMBIE_SPAWN_EGG, "<aqua>Criar mob", "<gray>Atributos, equipamento, drops, chefe,",
                    "<gray>domável, ataque à distância, modelo 3D...", "<yellow>Clique para começar"));
            inventory.setItem(14, icon(Material.IRON_SWORD, "<green>Meus itens", "<gray>Editar os itens do perfil <white>" + profile));
            inventory.setItem(15, icon(Material.STONE, "<green>Meus blocos", "<gray>Editar os blocos do perfil <white>" + profile));
            inventory.setItem(16, icon(Material.SKELETON_SKULL, "<green>Meus mobs", "<gray>Editar os mobs do perfil <white>" + profile));
            inventory.setItem(19, icon(Material.CHEST, "<yellow>Catálogo", "<gray>Pegue itens e blocos e invoque mobs",
                    "<gray>de todas as criações carregadas."));
            inventory.setItem(20, icon(Material.WRITABLE_BOOK, "<yellow>Perfis", "<gray>Trocar, criar, exportar e importar", "<gray>perfis de criações."));
            inventory.setItem(21, icon(Material.ENDER_CHEST, "<yellow>Exportar perfil atual", "<gray>Gera um código para levar a",
                    "<gray>outro servidor."));
            inventory.setItem(22, icon(Material.HOPPER, "<yellow>Importar perfil", "<gray>Mostra como importar um código", "<gray>ou arquivo."));
            inventory.setItem(23, icon(Material.PAINTING, "<yellow>Pacote de recursos", "<gray>Envia o pacote atual para você."));
            inventory.setItem(24, icon(Material.REPEATER, "<yellow>Recarregar", "<gray>Relê todos os arquivos e refaz o pacote."));
            inventory.setItem(25, icon(Material.BEACON, "<yellow>Atualizar plugin", "<gray>Procura uma versão nova no GitHub."));
            inventory.setItem(31, icon(Material.BOOK, "<white>Funcionalidades", "<gray>Lista tudo o que o plugin faz no chat."));
            fillEmpty();
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            switch (slot) {
                case 10 -> plugin.editor().create(player, CreationType.ITEM);
                case 11 -> plugin.editor().create(player, CreationType.BLOCK);
                case 12 -> plugin.editor().create(player, CreationType.MOB);
                case 14 -> openMine(player, CreationType.ITEM, 0);
                case 15 -> openMine(player, CreationType.BLOCK, 0);
                case 16 -> openMine(player, CreationType.MOB, 0);
                case 19 -> plugin.menu().open(player, CreationType.ITEM, 0);
                case 20 -> openProfiles(player);
                case 21 -> {
                    player.closeInventory();
                    exportProfile(player, plugin.profiles().active());
                }
                case 22 -> {
                    player.closeInventory();
                    say(player, "<gray>Para importar: <white>/cc perfil importar <código></white> ou "
                            + "<white>/cc perfil importar-arquivo <nome></white> (arquivo .cccode em perfis/_exportados).");
                }
                case 23 -> {
                    player.closeInventory();
                    if (plugin.resourcePack().send(player)) {
                        say(player, "<gray>Enviando o pacote de recursos…");
                    } else {
                        say(player, "<yellow>O servidor ainda não tem um pacote de recursos configurado.");
                    }
                }
                case 24 -> {
                    player.closeInventory();
                    var loaded = plugin.reloadAll();
                    say(player, "<green>Recarregado: <white>" + loaded.items() + "</white> item(ns), <white>" + loaded.blocks()
                            + "</white> bloco(s), <white>" + loaded.mobs() + "</white> mob(s), <white>" + loaded.recipes() + "</white> receita(s).");
                }
                case 25 -> {
                    player.closeInventory();
                    plugin.updater().checkNow(player);
                }
                case 31 -> {
                    player.closeInventory();
                    sendHelp(player);
                }
                default -> { }
            }
        }
    }

    // ── Criações do perfil ──

    private final class MineScreen extends Screen {
        private final CreationType type;
        private final int page;
        private final List<String> ids;

        MineScreen(CreationType type, int page) {
            super(HubGui.this.plugin, 6, "<dark_gray>" + switch (type) {
                case ITEM -> "Meus itens";
                case BLOCK -> "Meus blocos";
                case MOB -> "Meus mobs";
            } + " <gray>(" + HubGui.this.plugin.profiles().active() + ")");
            this.type = type;
            String profile = plugin.profiles().active();
            this.ids = new ArrayList<>(plugin.profiles().ids(profile, type));
            int pages = Math.max(1, (ids.size() + PAGE - 1) / PAGE);
            this.page = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < PAGE && this.page * PAGE + i < ids.size(); i++) {
                inventory.setItem(i, entry(ids.get(this.page * PAGE + i)));
            }
            if (ids.isEmpty()) {
                inventory.setItem(22, icon(Material.BARRIER, "<gray>Nada aqui ainda", "<dark_gray>Volte e use \"Criar\"."));
            }
            ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = PAGE; i < 54; i++) {
                inventory.setItem(i, filler);
            }
            inventory.setItem(49, icon(Material.ARROW, "<yellow>Voltar ao menu"));
            inventory.setItem(51, icon(Material.PAPER, "<gray>Página <white>" + (this.page + 1) + "/" + pages));
            if (this.page > 0) {
                inventory.setItem(45, icon(Material.ARROW, "<yellow>Página anterior"));
            }
            if (this.page < pages - 1) {
                inventory.setItem(53, icon(Material.ARROW, "<yellow>Próxima página"));
            }
        }

        private ItemStack entry(String id) {
            ItemStack stack;
            var found = plugin.registry().get(type, id);
            if (found.isPresent() && found.get() instanceof MobDefinition mob) {
                Material egg = Material.matchMaterial(mob.entity().name() + "_SPAWN_EGG");
                stack = new ItemStack(egg == null ? Material.PAPER : egg);
            } else if (found.isPresent() && found.get() instanceof ItemSource source) {
                stack = source.create(plugin, 1);
            } else {
                stack = new ItemStack(Material.BARRIER);
            }
            var meta = stack.getItemMeta();
            List<Component> lore = new ArrayList<>();
            lore.add(Screen.MM.deserialize("<dark_gray>" + id).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (found.isEmpty()) {
                lore.add(Screen.MM.deserialize("<red>Não carregou (veja o console)").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            lore.add(Screen.MM.deserialize("<yellow>Clique: editar").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            lore.add(Screen.MM.deserialize("<red>Shift + botão direito: apagar").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore);
            stack.setItemMeta(meta);
            return stack;
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            if (slot < 0) {
                return;
            }
            switch (slot) {
                case 45 -> openMine(player, type, page - 1);
                case 53 -> openMine(player, type, page + 1);
                case 49 -> openHub(player);
                default -> {
                    int index = page * PAGE + slot;
                    if (slot >= PAGE || index >= ids.size()) {
                        return;
                    }
                    String id = ids.get(index);
                    if (event.isShiftClick() && event.isRightClick()) {
                        try {
                            plugin.profiles().remove(plugin.profiles().active(), type, id);
                            plugin.reloadAll();
                            say(player, "<gray>'" + Screen.esc(id) + "' apagado (cópia em perfis/_lixeira).");
                        } catch (IOException e) {
                            say(player, "<red>Erro ao apagar: " + Screen.esc(String.valueOf(e.getMessage())));
                        }
                        openMine(player, type, page);
                    } else if (!event.isRightClick()) {
                        plugin.editor().edit(player, type, id);
                    }
                }
            }
        }
    }

    // ── Perfis ──

    private final class ProfilesScreen extends Screen {
        private final List<String> names;

        ProfilesScreen() {
            super(HubGui.this.plugin, 6, "<dark_gray>Perfis");
            this.names = plugin.profiles().names();
            String active = plugin.profiles().active();
            for (int i = 0; i < PAGE && i < names.size(); i++) {
                String name = names.get(i);
                ProfileManager p = plugin.profiles();
                inventory.setItem(i, icon(name.equals(active) ? Material.ENCHANTED_BOOK : Material.BOOK, "<gold>" + name, List.of(
                        "<gray>" + p.ids(name, CreationType.ITEM).size() + " itens, " + p.ids(name, CreationType.BLOCK).size()
                                + " blocos, " + p.ids(name, CreationType.MOB).size() + " mobs",
                        name.equals(active) ? "<green>Perfil atual" : "<yellow>Clique: usar este perfil",
                        "<aqua>Botão direito: exportar código",
                        "<red>Shift + botão direito: apagar (vai para a lixeira)"), name.equals(active)));
            }
            ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = PAGE; i < 54; i++) {
                inventory.setItem(i, filler);
            }
            inventory.setItem(45, icon(Material.ARROW, "<yellow>Voltar ao menu"));
            inventory.setItem(48, icon(Material.LIME_DYE, "<green>Novo perfil", "<gray>Cria um perfil vazio e passa a usá-lo."));
            inventory.setItem(50, icon(Material.HOPPER, "<yellow>Importar", "<gray>/cc perfil importar <código>", "<gray>/cc perfil importar-arquivo <nome>"));
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            if (slot < 0) {
                return;
            }
            if (slot == 45) {
                openHub(player);
            } else if (slot == 48) {
                plugin.ui().ask(player, "<yellow>Novo perfil</yellow>\n<gray>Escolha um nome (minúsculas, números e _). Exemplo: <white>meu_servidor",
                        text -> {
                            String name = text.toLowerCase(java.util.Locale.ROOT);
                            if (!ProfileManager.validName(name)) {
                                return "nome inválido (use minúsculas, números e _, sem começar com _).";
                            }
                            if (!plugin.profiles().create(name)) {
                                return "já existe um perfil com esse nome.";
                            }
                            plugin.profiles().setActive(name);
                            return null;
                        }, () -> openProfiles(player));
            } else if (slot == 50) {
                player.closeInventory();
                say(player, "<gray>Para importar: <white>/cc perfil importar <código></white> ou "
                        + "<white>/cc perfil importar-arquivo <nome></white>.");
            } else if (slot < PAGE && slot < names.size()) {
                String name = names.get(slot);
                if (event.isShiftClick() && event.isRightClick()) {
                    if (plugin.profiles().delete(name)) {
                        plugin.reloadAll();
                        say(player, "<gray>Perfil '" + Screen.esc(name) + "' movido para perfis/_lixeira.");
                    } else {
                        say(player, "<red>Não dá para apagar (é o único perfil ou deu erro).");
                    }
                    openProfiles(player);
                } else if (event.isRightClick()) {
                    player.closeInventory();
                    exportProfile(player, name);
                } else {
                    plugin.profiles().setActive(name);
                    openProfiles(player);
                }
            }
        }
    }

    /** Todas as criações de um tipo (usado pelo catálogo). */
    List<Creation> all(CreationType type) {
        return new ArrayList<>(plugin.registry().all(type));
    }
}
