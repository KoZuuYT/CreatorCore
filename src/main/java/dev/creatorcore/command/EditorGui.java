package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.command.Schema.Field;
import dev.creatorcore.command.Schema.Kind;
import dev.creatorcore.profile.ProfileManager;
import dev.creatorcore.registry.CreationType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Editor visual de itens, blocos e mobs. Cada criação é editada em um rascunho (YAML em memória), campo a campo,
 * e só é gravada no perfil ativo se passar na validação.
 */
public final class EditorGui {

    private static final int PAGE = 45;

    private final CreatorCorePlugin plugin;

    public EditorGui(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Rascunho de uma criação sendo editada. */
    static final class Session {
        final CreationType type;
        final String id;
        final boolean isNew;
        final String profile;
        final YamlConfiguration draft = new YamlConfiguration();
        final List<Field> fields;

        Session(CreatorCorePlugin plugin, CreationType type, String id, boolean isNew, String profile) {
            this.type = type;
            this.id = id;
            this.isNew = isNew;
            this.profile = profile;
            this.fields = Schema.fields(plugin, type);
        }
    }

    // ── Entrada ──

    /** Pergunta o id e abre o editor de uma criação nova. */
    public void create(Player player, CreationType type) {
        String label = switch (type) {
            case ITEM -> "item";
            case BLOCK -> "bloco";
            case MOB -> "mob";
        };
        Session[] result = new Session[1];
        plugin.ui().ask(player, "<yellow>Novo " + label + "</yellow> no perfil <white>" + plugin.profiles().active()
                        + "</white>.\n<gray>Escolha um <white>id</white> (minúsculas, números e _). Exemplo: <white>espada_rubi",
                text -> {
                    String id = text.toLowerCase(java.util.Locale.ROOT);
                    if (!dev.creatorcore.registry.CreationRegistry.isValidId(id)) {
                        return "id inválido (use minúsculas, números e _).";
                    }
                    boolean taken = type == CreationType.MOB
                            ? plugin.registry().get(CreationType.MOB, id).isPresent()
                            : plugin.registry().get(CreationType.ITEM, id).isPresent()
                                    || plugin.registry().get(CreationType.BLOCK, id).isPresent();
                    if (taken) {
                        return "já existe um item/bloco/mob com o id '" + id + "' (nesse grupo). Escolha outro.";
                    }
                    Session session = new Session(plugin, type, id, true, plugin.profiles().active());
                    defaults(session);
                    result[0] = session;
                    return null;
                },
                () -> {
                    if (result[0] != null) {
                        plugin.ui().go(player, new EditorScreen(result[0]));
                    } else {
                        plugin.hub().openHub(player);
                    }
                });
    }

    /** Abre o editor de uma criação do perfil ativo. */
    public void edit(Player player, CreationType type, String id) {
        String profile = plugin.profiles().active();
        ConfigurationSection saved = plugin.profiles().read(profile, type, id);
        if (saved == null) {
            player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>Não achei '" + Screen.esc(id) + "' no perfil."));
            return;
        }
        Session session = new Session(plugin, type, id, false, profile);
        copyInto(saved, session.draft);
        plugin.ui().go(player, new EditorScreen(session));
    }

    private void defaults(Session s) {
        switch (s.type) {
            case ITEM -> s.draft.set("material", "PAPER");
            case BLOCK -> {
                var models = plugin.blocks().states().names();
                if (!models.isEmpty()) {
                    s.draft.set("modelo", models.get(0));
                }
            }
            case MOB -> s.draft.set("tipo", "ZOMBIE");
        }
    }

    private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection section) {
                copyInto(section, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }

    // ── Rascunho ──

    /** Remove o valor e apaga as seções que ficarem vazias (uma seção vazia ainda ativaria o recurso). */
    static void unset(ConfigurationSection root, String path) {
        root.set(path, null);
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            ConfigurationSection section = root.getConfigurationSection(parent);
            if (section != null && section.getKeys(false).isEmpty()) {
                root.set(parent, null);
            } else {
                break;
            }
            dot = parent.lastIndexOf('.');
        }
    }

    private static boolean has(Session s, Field f) {
        return switch (f.kind()) {
            case GRID -> s.draft.contains("receita.forma") || s.draft.contains("receita.ingredientes");
            default -> s.draft.contains(f.path());
        };
    }

    private String valueText(Session s, Field f) {
        if (!has(s, f)) {
            return "<dark_gray>não definido";
        }
        switch (f.kind()) {
            case BOOL:
                return s.draft.getBoolean(f.path()) ? "<green>sim" : "<red>não";
            case LIST: {
                List<?> list = s.draft.getList(f.path(), List.of());
                return "<white>" + list.size() + " entrada(s)";
            }
            case MAP: {
                ConfigurationSection m = s.draft.getConfigurationSection(f.path());
                return "<white>" + (m == null ? 0 : m.getKeys(false).size()) + " entrada(s)";
            }
            case GRID:
                return "<green>definida";
            default:
                return "<white>" + Screen.esc(String.valueOf(s.draft.get(f.path())));
        }
    }

    private List<String> preview(Session s, Field f) {
        List<String> lines = new ArrayList<>();
        if (f.kind() == Kind.LIST && f.codec() != null) {
            for (Object o : s.draft.getList(f.path(), List.of())) {
                lines.add("<dark_gray>- <gray>" + Screen.esc(f.codec().show(o)));
                if (lines.size() >= 5) {
                    lines.add("<dark_gray>...");
                    break;
                }
            }
        } else if (f.kind() == Kind.MAP) {
            ConfigurationSection m = s.draft.getConfigurationSection(f.path());
            if (m != null) {
                for (String key : m.getKeys(false)) {
                    lines.add("<dark_gray>- <gray>" + Screen.esc(key + ": " + m.get(key)));
                    if (lines.size() >= 5) {
                        lines.add("<dark_gray>...");
                        break;
                    }
                }
            }
        }
        return lines;
    }

    private static List<String> wrap(String text, String color) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() > 42 && line.length() > 0) {
                lines.add(color + Screen.esc(line.toString()));
                line.setLength(0);
            }
            line.append(line.length() == 0 ? "" : " ").append(word);
        }
        if (line.length() > 0) {
            lines.add(color + Screen.esc(line.toString()));
        }
        return lines;
    }

    // ── Gravação ──

    private static Map<String, Object> plain(ConfigurationSection section) {
        return ProfileManager.plain(section);
    }

    /** Remove seções vazias do rascunho. */
    private static void prune(ConfigurationSection section) {
        for (String key : new ArrayList<>(section.getKeys(false))) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                prune(child);
                if (child.getKeys(false).isEmpty()) {
                    section.set(key, null);
                }
            }
        }
    }

    private void save(Player player, Session s) {
        prune(s.draft);
        String error = plugin.itemLoader().validate(s.type, s.id, s.draft);
        if (error != null) {
            player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>Não dá para salvar ainda:</red> <white>"
                    + Screen.esc(error)));
            return;
        }
        // Ordena as chaves como no editor (arquivo mais legível).
        Map<String, Object> all = plain(s.draft);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (Field f : s.fields) {
            String top = f.path().split("\\.")[0];
            if (all.containsKey(top)) {
                ordered.put(top, all.remove(top));
            }
        }
        ordered.putAll(all);
        try {
            plugin.profiles().save(s.profile, s.type, s.id, ordered);
        } catch (IOException e) {
            player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>Erro ao gravar: " + Screen.esc(String.valueOf(e.getMessage()))));
            return;
        }
        var loaded = plugin.reloadAll();
        boolean ok = plugin.registry().get(s.type, s.id).isPresent();
        player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> "
                + (ok ? "<green>Salvo no perfil <white>" + s.profile + "</white>: <white>" + s.id + "</white>."
                        : "<red>Gravado, mas '" + s.id + "' não carregou. Veja o console.")
                + " <gray>(" + loaded.items() + " itens, " + loaded.blocks() + " blocos, " + loaded.mobs() + " mobs)"));
        plugin.hub().openMine(player, s.type, 0);
    }

    // ── Telas ──

    /** Tela principal do editor: um botão por campo. */
    final class EditorScreen extends Screen {
        private final Session s;
        private int page;

        EditorScreen(Session s) {
            super(EditorGui.this.plugin, 6, "<dark_gray>Editor: <black>" + s.id);
            this.s = s;
            refresh();
        }

        private void refresh() {
            inventory.clear();
            int pages = Math.max(1, (s.fields.size() + PAGE - 1) / PAGE);
            page = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < PAGE; i++) {
                int index = page * PAGE + i;
                if (index >= s.fields.size()) {
                    break;
                }
                Field f = s.fields.get(index);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Valor: " + valueText(s, f));
                lore.addAll(preview(s, f));
                lore.add(" ");
                lore.addAll(wrap(f.help(), "<gray>"));
                lore.add(" ");
                lore.add(switch (f.kind()) {
                    case BOOL -> "<yellow>Clique: alternar";
                    case ENUM -> "<yellow>Clique: próxima opção";
                    case LIST, MAP -> "<yellow>Clique: ver e adicionar";
                    case GRID -> "<yellow>Clique: montar a grade";
                    default -> "<yellow>Clique: digitar no chat";
                });
                lore.add("<red>Botão direito: limpar");
                inventory.setItem(i, icon(f.icon(), "<gold>" + f.label(), lore, has(s, f)));
            }
            ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = PAGE; i < 54; i++) {
                inventory.setItem(i, filler);
            }
            String kind = switch (s.type) {
                case ITEM -> "item";
                case BLOCK -> "bloco";
                case MOB -> "mob";
            };
            inventory.setItem(46, icon(Material.PAPER, "<white>" + s.id,
                    "<gray>" + (s.isNew ? "Novo " : "Editando ") + kind, "<gray>Perfil: <white>" + s.profile,
                    "<dark_gray>Só é gravado ao clicar em Salvar."));
            inventory.setItem(48, icon(Material.LIME_CONCRETE, "<green>Salvar", "<gray>Valida e grava no perfil.", "<gray>O jogo recarrega as criações."));
            inventory.setItem(50, icon(Material.RED_CONCRETE, "<red>Sair sem salvar", "<gray>Descarta as mudanças."));
            inventory.setItem(51, icon(Material.PAPER, "<gray>Página <white>" + (page + 1) + "/" + pages));
            if (page > 0) {
                inventory.setItem(45, icon(Material.ARROW, "<yellow>Página anterior"));
            }
            if (page < pages - 1) {
                inventory.setItem(53, icon(Material.ARROW, "<yellow>Próxima página"));
            }
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            if (slot < 0) {
                return;
            }
            switch (slot) {
                case 45 -> {
                    page--;
                    refresh();
                }
                case 53 -> {
                    page++;
                    refresh();
                }
                case 48 -> save(player, s);
                case 50 -> plugin.hub().openHub(player);
                default -> {
                    int index = page * PAGE + slot;
                    if (slot < PAGE && index < s.fields.size()) {
                        clickField(player, s.fields.get(index), event.isRightClick());
                    }
                }
            }
        }

        private void clickField(Player player, Field f, boolean right) {
            if (right) {
                if (f.kind() == Kind.GRID) {
                    unset(s.draft, "receita.forma");
                    unset(s.draft, "receita.ingredientes");
                } else {
                    unset(s.draft, f.path());
                }
                refresh();
                return;
            }
            switch (f.kind()) {
                case BOOL -> {
                    s.draft.set(f.path(), !s.draft.getBoolean(f.path(), false));
                    refresh();
                }
                case ENUM -> {
                    int at = f.options().indexOf(s.draft.getString(f.path(), ""));
                    s.draft.set(f.path(), f.options().get((at + 1) % f.options().size()));
                    refresh();
                }
                case LIST, MAP -> plugin.ui().go(player, new ListScreen(this, s, f));
                case GRID -> plugin.ui().go(player, new GridScreen(this, s));
                default -> askValue(player, f);
            }
        }

        private void askValue(Player player, Field f) {
            String range = f.kind() == Kind.INT || f.kind() == Kind.NUM
                    ? "\n<gray>Valor de <white>" + trim(f.min()) + "</white> a <white>" + trim(f.max()) + "</white>." : "";
            String example = f.example().isBlank() ? "" : "\n<gray>Exemplo: <white>" + Screen.esc(f.example());
            plugin.ui().ask(player, "<yellow>" + f.label() + "</yellow>\n<gray>" + Screen.esc(f.help()) + range + example,
                    text -> {
                        switch (f.kind()) {
                            case INT -> {
                                int v;
                                try {
                                    v = Integer.parseInt(text.strip());
                                } catch (NumberFormatException e) {
                                    return "digite um número inteiro.";
                                }
                                if (v < f.min() || v > f.max()) {
                                    return "o valor deve ficar entre " + trim(f.min()) + " e " + trim(f.max()) + ".";
                                }
                                s.draft.set(f.path(), v);
                            }
                            case NUM -> {
                                double v;
                                try {
                                    v = Double.parseDouble(text.strip().replace(',', '.'));
                                } catch (NumberFormatException e) {
                                    return "digite um número (ex.: 1.5).";
                                }
                                if (v < f.min() || v > f.max()) {
                                    return "o valor deve ficar entre " + trim(f.min()) + " e " + trim(f.max()) + ".";
                                }
                                s.draft.set(f.path(), v);
                            }
                            default -> {
                                String error = f.check() == null ? null : f.check().apply(text.strip());
                                if (error != null) {
                                    return error;
                                }
                                String value = f.path().equals("material") || f.path().equals("tipo")
                                        ? text.strip().toUpperCase(java.util.Locale.ROOT) : text.strip();
                                s.draft.set(f.path(), value);
                            }
                        }
                        return null;
                    }, () -> plugin.ui().go(player, this));
        }

        @Override
        void onClose(Player player) {
        }

        void reopen(Player player) {
            refresh();
            plugin.ui().go(player, this);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Lista ou mapa de um campo: ver, adicionar e remover entradas. */
    final class ListScreen extends Screen {
        private final EditorScreen back;
        private final Session s;
        private final Field f;

        ListScreen(EditorScreen back, Session s, Field f) {
            super(EditorGui.this.plugin, 6, "<dark_gray>" + f.label());
            this.back = back;
            this.s = s;
            this.f = f;
            refresh();
        }

        private List<String> shown() {
            List<String> lines = new ArrayList<>();
            if (f.kind() == Kind.LIST) {
                for (Object o : s.draft.getList(f.path(), List.of())) {
                    lines.add(f.codec().show(o));
                }
            } else {
                ConfigurationSection m = s.draft.getConfigurationSection(f.path());
                if (m != null) {
                    for (String key : m.getKeys(false)) {
                        lines.add(key + " " + m.get(key));
                    }
                }
            }
            return lines;
        }

        private void refresh() {
            inventory.clear();
            List<String> entries = shown();
            for (int i = 0; i < PAGE && i < entries.size(); i++) {
                inventory.setItem(i, icon(Material.PAPER, "<white>" + Screen.esc(entries.get(i)),
                        "<red>Botão direito: remover"));
            }
            ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = PAGE; i < 54; i++) {
                inventory.setItem(i, filler);
            }
            inventory.setItem(45, icon(Material.ARROW, "<yellow>Voltar", "<gray>Volta ao editor."));
            List<String> help = new ArrayList<>(wrap(f.help(), "<gray>"));
            if (!f.example().isBlank()) {
                help.add("<gray>Exemplo: <white>" + Screen.esc(f.example()));
            }
            inventory.setItem(49, icon(Material.LIME_DYE, "<green>Adicionar", help));
            inventory.setItem(53, icon(Material.BARRIER, "<red>Limpar tudo", "<gray>Remove todas as entradas."));
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            if (slot < 0) {
                return;
            }
            if (slot == 45) {
                back.reopen(player);
            } else if (slot == 53) {
                unset(s.draft, f.path());
                refresh();
            } else if (slot == 49) {
                add(player);
            } else if (slot < PAGE && event.isRightClick() && slot < shown().size()) {
                remove(slot);
                refresh();
            }
        }

        private void remove(int index) {
            if (f.kind() == Kind.LIST) {
                List<Object> list = new ArrayList<>(s.draft.getList(f.path(), List.of()));
                list.remove(index);
                if (list.isEmpty()) {
                    unset(s.draft, f.path());
                } else {
                    s.draft.set(f.path(), list);
                }
            } else {
                ConfigurationSection m = s.draft.getConfigurationSection(f.path());
                if (m != null) {
                    String key = new ArrayList<>(m.getKeys(false)).get(index);
                    unset(s.draft, f.path() + "." + key);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void add(Player player) {
            if (shown().size() >= PAGE) {
                player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>Limite de " + PAGE + " entradas."));
                return;
            }
            String example = f.example().isBlank() ? "" : "\n<gray>Exemplo: <white>" + Screen.esc(f.example());
            plugin.ui().ask(player, "<yellow>" + f.label() + "</yellow> — adicionar\n<gray>" + Screen.esc(f.help()) + example,
                    text -> {
                        Object parsed = f.codec().parse(text);
                        if (f.kind() == Kind.LIST) {
                            List<Object> list = new ArrayList<>(s.draft.getList(f.path(), List.of()));
                            list.add(parsed);
                            s.draft.set(f.path(), list);
                        } else {
                            Map.Entry<String, Object> entry = (Map.Entry<String, Object>) parsed;
                            s.draft.set(f.path() + "." + entry.getKey(), entry.getValue());
                        }
                        return null;
                    }, () -> {
                        refresh();
                        plugin.ui().go(player, this);
                    });
        }
    }

    /** Grade 3x3 da receita: clique com um item no cursor (ou no seu inventário) para copiá-lo; nada é consumido. */
    final class GridScreen extends Screen {
        private static final int[] GRID = {2, 3, 4, 11, 12, 13, 20, 21, 22};
        private final EditorScreen back;
        private final Session s;

        GridScreen(EditorScreen back, Session s) {
            super(EditorGui.this.plugin, 3, "<dark_gray>Receita de " + s.id);
            this.back = back;
            this.s = s;
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " "));
            }
            for (int slot : GRID) {
                inventory.setItem(slot, null);
            }
            inventory.setItem(14, icon(Material.ARROW, "<gray>→"));
            inventory.setItem(24, icon(Material.LIME_CONCRETE, "<green>Confirmar", "<gray>Grava a receita no rascunho."));
            inventory.setItem(25, icon(Material.YELLOW_CONCRETE, "<yellow>Limpar grade"));
            inventory.setItem(26, icon(Material.RED_CONCRETE, "<red>Voltar sem mudar"));
            inventory.setItem(18, icon(Material.PAPER, "<white>Como usar",
                    "<gray>Pegue um item (cursor) e clique", "<gray>num quadrado: ele é copiado.",
                    "<gray>Ou clique num item do seu inventário.", "<gray>Botão direito num quadrado limpa.",
                    "<dark_gray>Itens do CreatorCore viram cc:<id>."));
            load();
        }

        private void load() {
            if (!s.draft.contains("receita.forma") && !s.draft.contains("receita.ingredientes")) {
                return;
            }
            if (s.draft.isList("receita.ingredientes")) {
                int i = 0;
                for (String spec : s.draft.getStringList("receita.ingredientes")) {
                    if (i < 9) {
                        place(GRID[i++], spec);
                    }
                }
                return;
            }
            ConfigurationSection map = s.draft.getConfigurationSection("receita.ingredientes");
            List<String> shape = s.draft.getStringList("receita.forma");
            for (int row = 0; row < shape.size() && row < 3; row++) {
                for (int col = 0; col < shape.get(row).length() && col < 3; col++) {
                    char c = shape.get(row).charAt(col);
                    if (c != ' ' && map != null) {
                        place(GRID[row * 3 + col], map.getString(String.valueOf(c), ""));
                    }
                }
            }
        }

        private void place(int slot, String spec) {
            ItemStack stack = plugin.itemLoader().resolveStack(spec, 1);
            if (stack != null) {
                inventory.setItem(slot, stack);
            }
        }

        private int indexOf(int slot) {
            for (int i = 0; i < GRID.length; i++) {
                if (GRID[i] == slot) {
                    return i;
                }
            }
            return -1;
        }

        private String specOf(ItemStack stack) {
            String id = stack.hasItemMeta() ? stack.getItemMeta().getPersistentDataContainer().get(plugin.idKey(), PersistentDataType.STRING) : null;
            return id != null ? "cc:" + id : stack.getType().name();
        }

        @Override
        void onClick(Player player, InventoryClickEvent event, int slot) {
            if (slot < 0) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) {
                    for (int grid : GRID) {
                        if (inventory.getItem(grid) == null) {
                            ItemStack copy = clicked.clone();
                            copy.setAmount(1);
                            inventory.setItem(grid, copy);
                            break;
                        }
                    }
                }
                return;
            }
            int cell = indexOf(slot);
            if (cell >= 0) {
                ItemStack cursor = event.getCursor();
                if (!event.isRightClick() && !cursor.getType().isAir()) {
                    ItemStack copy = cursor.clone();
                    copy.setAmount(1);
                    inventory.setItem(slot, copy);
                } else {
                    inventory.setItem(slot, null);
                }
            } else if (slot == 25) {
                for (int grid : GRID) {
                    inventory.setItem(grid, null);
                }
            } else if (slot == 26) {
                back.reopen(player);
            } else if (slot == 24) {
                confirm(player);
            }
        }

        private void confirm(Player player) {
            String[] cells = new String[9];
            int minRow = 3;
            int maxRow = -1;
            int minCol = 3;
            int maxCol = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inventory.getItem(GRID[i]);
                if (stack != null && !stack.getType().isAir()) {
                    cells[i] = specOf(stack);
                    minRow = Math.min(minRow, i / 3);
                    maxRow = Math.max(maxRow, i / 3);
                    minCol = Math.min(minCol, i % 3);
                    maxCol = Math.max(maxCol, i % 3);
                }
            }
            if (maxRow < 0) {
                player.sendMessage(Screen.MM.deserialize("<gold>[CreatorCore]</gold> <red>A grade está vazia."));
                return;
            }
            unset(s.draft, "receita.forma");
            unset(s.draft, "receita.ingredientes");
            if ("shapeless".equals(s.draft.getString("receita.tipo"))) {
                List<String> all = new ArrayList<>();
                for (String c : cells) {
                    if (c != null) {
                        all.add(c);
                    }
                }
                s.draft.set("receita.ingredientes", all);
            } else {
                Map<String, Character> letters = new LinkedHashMap<>();
                List<String> shape = new ArrayList<>();
                for (int row = minRow; row <= maxRow; row++) {
                    StringBuilder line = new StringBuilder();
                    for (int col = minCol; col <= maxCol; col++) {
                        String spec = cells[row * 3 + col];
                        if (spec == null) {
                            line.append(' ');
                        } else {
                            line.append(letters.computeIfAbsent(spec, k -> (char) ('A' + letters.size())));
                        }
                    }
                    shape.add(line.toString());
                }
                s.draft.set("receita.forma", shape);
                ConfigurationSection ingredients = s.draft.createSection("receita.ingredientes");
                letters.forEach((spec, letter) -> ingredients.set(String.valueOf(letter), spec));
            }
            back.reopen(player);
        }
    }
}
