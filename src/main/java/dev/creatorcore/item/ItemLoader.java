package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.block.BlockDefinition;
import dev.creatorcore.block.DropSpec;
import dev.creatorcore.block.ToolInfo;
import dev.creatorcore.mob.MobDefinition;
import dev.creatorcore.mob.SpawnSpec;
import dev.creatorcore.registry.CreationRegistry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import dev.creatorcore.registry.CreationType;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Carrega os itens de {@code plugins/CreatorCore/items/*.yml} (ver o {@code exemplo.yml} gerado
 * na primeira execução) e registra suas receitas no servidor.
 */
public final class ItemLoader {

    /** Resultado de um carregamento. */
    public record LoadResult(int items, int blocks, int mobs, int recipes) {
    }

    private final CreatorCorePlugin plugin;
    private final CreationRegistry registry;
    /** Receitas registradas por nós, para removê-las ao recarregar. */
    private final Set<NamespacedKey> recipeKeys = new LinkedHashSet<>();

    public ItemLoader(CreatorCorePlugin plugin, CreationRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** Copia o exemplo na primeira execução (só se a pasta ainda não existir). */
    public void saveDefaults() {
        File dir = new File(plugin.getDataFolder(), "items");
        if (!dir.exists()) {
            plugin.saveResource("items/exemplo.yml", false);
        }
        if (!new File(plugin.getDataFolder(), "blocks").exists()) {
            plugin.saveResource("blocks/exemplo.yml", false);
        }
        if (!new File(plugin.getDataFolder(), "models").exists()) {
            plugin.saveResource("models/golem_teste.bbmodel", false);
            plugin.saveResource("models/robo_geo.geo.json", false);
            plugin.saveResource("models/robo_geo.png", false);
            plugin.saveResource("models/robo_geo.animation.json", false);
        }
        if (!new File(plugin.getDataFolder(), "armor").exists()) {
            plugin.saveResource("armor/rubi_1.png", false);
            plugin.saveResource("armor/rubi_2.png", false);
        }
        if (!new File(plugin.getDataFolder(), "mobs").exists()) {
            plugin.saveResource("mobs/exemplo.yml", false);
        }
    }

    /** Remove as receitas registradas por este plugin (ao desligar). */
    public void unregisterRecipes() {
        for (NamespacedKey key : recipeKeys) {
            Bukkit.removeRecipe(key);
        }
        recipeKeys.clear();
    }

    /** Dá aos jogadores as receitas do plugin no livro de receitas. */
    public void discoverFor(Player player) {
        player.discoverRecipes(recipeKeys);
    }

    /** Recarrega itens, blocos e receitas. Entradas inválidas são ignoradas com aviso. */
    public LoadResult load() {
        registry.clear(CreationType.ITEM);
        registry.clear(CreationType.BLOCK);
        registry.clear(CreationType.MOB);
        unregisterRecipes();
        plugin.models().load(); // antes de tudo: os itens consultam os modelos importados
        int items = loadFolder("items", CreationType.ITEM);
        int blocks = loadFolder("blocks", CreationType.BLOCK);
        int mobs = loadFolder("mobs", CreationType.MOB);
        plugin.blocks().rebuild(registry);
        // Receitas e drops só depois de tudo carregado, pois um ingrediente pode ser outro item/bloco (cc:<id>).
        int recipes = 0;
        for (CreationType type : new CreationType[] {CreationType.ITEM, CreationType.BLOCK}) {
            for (var creation : registry.all(type)) {
                if (creation instanceof ItemSource item && item.recipe() != null && registerRecipe(item)) {
                    recipes++;
                }
                if (creation instanceof ItemSource item) {
                    recipes += registerCooking(item);
                }
            }
        }
        for (var creation : registry.all(CreationType.BLOCK)) {
            BlockDefinition block = (BlockDefinition) creation;
            for (DropSpec drop : block.drops()) {
                if (resolveStack(drop.item(), 1) == null) {
                    plugin.getLogger().warning("Bloco '" + block.id() + "': drop '" + drop.item() + "' não existe.");
                }
            }
        }
        for (var creation : registry.all(CreationType.MOB)) {
            MobDefinition mob = (MobDefinition) creation;
            if (mob.model() != null && !plugin.models().has(mob.model())) {
                plugin.getLogger().warning("Mob '" + mob.id() + "': modelo '" + mob.model()
                        + "' não encontrado em models/ (o mob usa o visual do mob base).");
            }
            for (DropSpec drop : mob.drops()) {
                if (resolveStack(drop.item(), 1) == null) {
                    plugin.getLogger().warning("Mob '" + mob.id() + "': drop '" + drop.item() + "' não existe.");
                }
            }
            for (String equip : mob.equipment().values()) {
                if (resolveStack(equip, 1) == null) {
                    plugin.getLogger().warning("Mob '" + mob.id() + "': equipamento '" + equip + "' não existe.");
                }
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            discoverFor(online);
        }
        return new LoadResult(items, blocks, mobs, recipes);
    }

    /** Lê todos os .yml de uma pasta de definições. @return quantas criações foram registradas */
    private int loadFolder(String folder, CreationType type) {
        File dir = new File(plugin.getDataFolder(), folder);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        int loaded = 0;
        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(id);
                if (section == null) {
                    warn(file, id, "não é uma seção");
                    continue;
                }
                try {
                    if (type == CreationType.BLOCK && registry.get(CreationType.ITEM, id).isPresent()) {
                        throw new IllegalArgumentException("id já usado por um item");
                    }
                    registry.register(switch (type) {
                        case ITEM -> parse(id, section);
                        case BLOCK -> parseBlock(id, section);
                        case MOB -> parseMob(id, section);
                    });
                    loaded++;
                } catch (IllegalArgumentException e) {
                    warn(file, id, e.getMessage());
                }
            }
        }
        return loaded;
    }

    private boolean registerRecipe(ItemSource item) {
        try {
            Recipe recipe = buildRecipe(item);
            if (!Bukkit.addRecipe(recipe)) {
                throw new IllegalArgumentException("o servidor recusou a receita");
            }
            recipeKeys.add(recipeKey(item));
            return true;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Receita de '" + item.id() + "' ignorada (" + e.getMessage() + ").");
            return false;
        }
    }

    /** Registra as receitas de cozimento do item. @return quantas foram aceitas */
    private int registerCooking(ItemSource item) {
        int ok = 0;
        List<CookSpec> specs = item.cooking();
        for (int i = 0; i < specs.size(); i++) {
            CookSpec spec = specs.get(i);
            try {
                RecipeChoice input = choice(spec.input());
                String prefix = item.type() == CreationType.BLOCK ? "cozinhar_bloco_" : "cozinhar_";
                NamespacedKey key = new NamespacedKey(plugin, prefix + item.id() + "_" + i);
                var result = item.create(plugin, spec.amount());
                Recipe recipe = switch (spec.kind()) {
                    case "blasting" -> new org.bukkit.inventory.BlastingRecipe(key, result, input, spec.xp(), spec.time());
                    case "smoking" -> new org.bukkit.inventory.SmokingRecipe(key, result, input, spec.xp(), spec.time());
                    case "campfire" -> new org.bukkit.inventory.CampfireRecipe(key, result, input, spec.xp(), spec.time());
                    default -> new org.bukkit.inventory.FurnaceRecipe(key, result, input, spec.xp(), spec.time());
                };
                if (!Bukkit.addRecipe(recipe)) {
                    throw new IllegalArgumentException("o servidor recusou a receita");
                }
                recipeKeys.add(key);
                ok++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Cozimento " + (i + 1) + " de '" + item.id() + "' ignorado (" + e.getMessage() + ").");
            }
        }
        return ok;
    }

    // ── Blocos ──────────────────────────────────────────────────

    private BlockDefinition parseBlock(String id, ConfigurationSection s) {
        String model = s.getString("modelo", id);
        Integer state = plugin.blocks().states().state(model);
        if (state == null) {
            throw new IllegalArgumentException("modelo '" + model + "' não existe em blocks.json (veja tools/pack/build.py)");
        }
        double hardness = s.getDouble("dureza", 1.0);
        ToolInfo.Type tool = ToolInfo.Type.parse(s.getString("ferramenta", "none"));
        if (tool == null) {
            throw new IllegalArgumentException("ferramenta inválida '" + s.getString("ferramenta") + "'");
        }
        int tier = ToolInfo.parseTier(s.getString("nivel", "wood"));
        if (tier < 0) {
            throw new IllegalArgumentException("nivel inválido '" + s.getString("nivel") + "'");
        }
        boolean requiresTool = s.getBoolean("requer-ferramenta", false);
        if (requiresTool && tool == ToolInfo.Type.NONE) {
            throw new IllegalArgumentException("requer-ferramenta exige o campo ferramenta");
        }
        List<DropSpec> drops = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("drops")) {
            drops.add(parseDrop(raw));
        }
        return new BlockDefinition(id, s.getString("nome", ""), List.copyOf(s.getStringList("lore")), model, state,
                hardness, tool, tier, requiresTool, List.copyOf(drops), parseRecipe(s), parseCooking(s));
    }

    // ── Mobs ────────────────────────────────────────────────────

    private MobDefinition parseMob(String id, ConfigurationSection s) {
        String typeName = s.getString("tipo", "");
        EntityType entity = null;
        for (EntityType candidate : EntityType.values()) {
            if (candidate != EntityType.UNKNOWN && candidate.name().equalsIgnoreCase(typeName)) {
                entity = candidate;
            }
        }
        if (entity == null || entity.getEntityClass() == null
                || !LivingEntity.class.isAssignableFrom(entity.getEntityClass())
                || entity == EntityType.PLAYER || entity == EntityType.ARMOR_STAND || entity == EntityType.GIANT) {
            throw new IllegalArgumentException("tipo inválido '" + typeName + "' (use um mob vivo, ex.: ZOMBIE)");
        }
        Map<Attribute, Double> attributes = new LinkedHashMap<>();
        ConfigurationSection attrs = s.getConfigurationSection("atributos");
        if (attrs != null) {
            for (String name : attrs.getKeys(false)) {
                Attribute attribute = Registry.ATTRIBUTE.get(namespaced(name));
                if (attribute == null || !attrs.isDouble(name) && !attrs.isInt(name)) {
                    throw new IllegalArgumentException("atributo inválido '" + name + "' (ex.: max_health: 40)");
                }
                attributes.put(attribute, attrs.getDouble(name));
            }
        }
        Map<EquipmentSlot, String> equipment = new LinkedHashMap<>();
        ConfigurationSection equip = s.getConfigurationSection("equipamento");
        if (equip != null) {
            for (String slotName : equip.getKeys(false)) {
                EquipmentSlot slot = switch (slotName.toLowerCase(Locale.ROOT)) {
                    case "mainhand" -> EquipmentSlot.HAND;
                    case "offhand" -> EquipmentSlot.OFF_HAND;
                    case "head" -> EquipmentSlot.HEAD;
                    case "chest" -> EquipmentSlot.CHEST;
                    case "legs" -> EquipmentSlot.LEGS;
                    case "feet" -> EquipmentSlot.FEET;
                    default -> throw new IllegalArgumentException("slot de equipamento inválido '" + slotName
                            + "' (mainhand, offhand, head, chest, legs, feet)");
                };
                equipment.put(slot, equip.getString(slotName, ""));
            }
        }
        List<DropSpec> drops = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("drops")) {
            drops.add(parseDrop(raw));
        }
        return new MobDefinition(id, entity, s.getString("nome", ""), Map.copyOf(attributes), Map.copyOf(equipment),
                List.copyOf(drops), s.getBoolean("drops-vanilla", false), s.getInt("xp", -1),
                s.getBoolean("brilho", false), s.getBoolean("silencioso", false), parseSpawn(s),
                parseModel(s), s.getDouble("escala-modelo", 1.0), s.getDouble("deslocamento-y", 0.0),
                s.getDouble("rotacao-modelo", 180.0), parseAnimations(s));
    }

    /** {@code animacoes:} papel (parado, andando, ataque, morte) -> nome da animação do modelo. */
    private static Map<String, String> parseAnimations(ConfigurationSection s) {
        ConfigurationSection n = s.getConfigurationSection("animacoes");
        Map<String, String> result = new LinkedHashMap<>();
        if (n != null) {
            for (String role : n.getKeys(false)) {
                if (!Set.of("parado", "andando", "ataque", "morte").contains(role)) {
                    throw new IllegalArgumentException("animacoes: papel inválido '" + role + "' (parado, andando, ataque, morte)");
                }
                result.put(role, n.getString(role, ""));
            }
        }
        return Map.copyOf(result);
    }

    private static SpawnSpec parseSpawn(ConfigurationSection s) {
        ConfigurationSection n = s.getConfigurationSection("spawn");
        if (n == null) {
            return null;
        }
        double chance = n.getDouble("chance", 0.1);
        if (chance <= 0 || chance > 1) {
            throw new IllegalArgumentException("spawn: chance inválida (use de 0 a 1, ex.: 0.2)");
        }
        int limit = n.getInt("limite", 10);
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("spawn: limite inválido (1 a 1000)");
        }
        return new SpawnSpec(lowerSet(n.getStringList("mundos"), false), lowerSet(n.getStringList("biomas"), true),
                chance, limit);
    }

    private static Set<String> lowerSet(List<String> values, boolean lower) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String v = value.strip();
            if (lower) {
                v = v.toLowerCase(Locale.ROOT);
                int colon = v.indexOf(':');
                v = colon < 0 ? v : v.substring(colon + 1);
            }
            result.add(v);
        }
        return result;
    }

    private static DropSpec parseDrop(Map<?, ?> raw) {
        Object item = raw.get("item");
        if (item == null || String.valueOf(item).isBlank()) {
            throw new IllegalArgumentException("drop sem 'item'");
        }
        String amount = raw.get("quantidade") == null ? "1" : String.valueOf(raw.get("quantidade")).strip();
        try {
            int dash = amount.indexOf('-', 1);
            int min = Integer.parseInt(dash < 0 ? amount : amount.substring(0, dash).strip());
            int max = dash < 0 ? min : Integer.parseInt(amount.substring(dash + 1).strip());
            if (min < 0 || max < min || max > 64) {
                throw new NumberFormatException();
            }
            return new DropSpec(String.valueOf(item).strip(), min, max);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("quantidade de drop inválida '" + amount + "' (use 2 ou 1-3, até 64)");
        }
    }

    // ── Itens ───────────────────────────────────────────────────

    private ItemDefinition parse(String id, ConfigurationSection s) {
        String materialName = s.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("material inválido '" + materialName + "'");
        }
        return new ItemDefinition(id, material, s.getString("nome", ""), List.copyOf(s.getStringList("lore")),
                parseAttributes(s), parseEnchantments(s.getConfigurationSection("encantamentos")),
                s.getBoolean("inquebravel", false), s.getBoolean("brilho", false), parseModel(s), parseRecipe(s), parseCooking(s),
                parseAbilities(s), parseArmor(s));
    }

    /** {@code armadura:} {slot: head|chest|legs|feet, textura, protecao, resistencia}. */
    private ArmorSpec parseArmor(ConfigurationSection s) {
        ConfigurationSection a = s.getConfigurationSection("armadura");
        if (a == null) {
            return null;
        }
        org.bukkit.inventory.EquipmentSlot slot = switch (a.getString("slot", "").toLowerCase(Locale.ROOT)) {
            case "head" -> org.bukkit.inventory.EquipmentSlot.HEAD;
            case "chest" -> org.bukkit.inventory.EquipmentSlot.CHEST;
            case "legs" -> org.bukkit.inventory.EquipmentSlot.LEGS;
            case "feet" -> org.bukkit.inventory.EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("armadura: 'slot' deve ser head, chest, legs ou feet");
        };
        String texture = a.getString("textura", "");
        if (!plugin.models().hasArmor(texture)) {
            throw new IllegalArgumentException("armadura: textura '" + texture
                    + "' não encontrada (faltam armor/" + texture + "_1.png e armor/" + texture + "_2.png)");
        }
        double defense = a.getDouble("protecao", 0);
        double toughness = a.getDouble("resistencia", 0);
        if (defense < 0 || defense > 30 || toughness < 0 || toughness > 20) {
            throw new IllegalArgumentException("armadura: protecao (0-30) ou resistencia (0-20) inválidas");
        }
        return new ArmorSpec(slot, texture, defense, toughness);
    }

    private static final Set<String> TRIGGERS = Set.of("right_click", "left_click", "sneak_right_click", "hit");
    private static final Set<String> ACTION_TYPES = Set.of("efeito", "curar", "dano", "comando", "projetil", "som",
            "raio", "impulso");

    /** {@code habilidades:} lista de {gatilho, recarga, consumir, acoes: [...]}. */
    private static List<AbilitySpec> parseAbilities(ConfigurationSection s) {
        List<AbilitySpec> abilities = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("habilidades")) {
            String trigger = String.valueOf(raw.get("gatilho")).toLowerCase(Locale.ROOT);
            if (!TRIGGERS.contains(trigger)) {
                throw new IllegalArgumentException("habilidade: gatilho inválido '" + trigger
                        + "' (right_click, left_click, sneak_right_click ou hit)");
            }
            double cooldown = number(raw.get("recarga"), 0, "recarga");
            if (cooldown < 0 || cooldown > 86400) {
                throw new IllegalArgumentException("habilidade: recarga inválida (segundos, 0 a 86400)");
            }
            boolean consume = raw.get("consumir") != null && Boolean.parseBoolean(String.valueOf(raw.get("consumir")));
            List<AbilitySpec.Action> actions = new ArrayList<>();
            if (!(raw.get("acoes") instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException("habilidade sem 'acoes'");
            }
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("habilidade: cada ação deve ser um mapa {tipo: ...}");
                }
                actions.add(parseAction(map, trigger));
            }
            abilities.add(new AbilitySpec(trigger, cooldown, consume, List.copyOf(actions)));
        }
        return List.copyOf(abilities);
    }

    private static double number(Object value, double fallback, String field) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("habilidade: '" + field + "' não é um número");
        }
    }

    private static AbilitySpec.Action parseAction(Map<?, ?> m, String trigger) {
        String type = String.valueOf(m.get("tipo")).toLowerCase(Locale.ROOT);
        if (!ACTION_TYPES.contains(type)) {
            throw new IllegalArgumentException("ação: tipo inválido '" + type + "' (" + String.join(", ", new java.util.TreeSet<>(ACTION_TYPES)) + ")");
        }
        boolean onTarget = "alvo".equalsIgnoreCase(String.valueOf(m.get("em")));
        if (m.get("em") != null && !onTarget && !"jogador".equalsIgnoreCase(String.valueOf(m.get("em")))) {
            throw new IllegalArgumentException("ação: 'em' deve ser jogador ou alvo");
        }
        org.bukkit.potion.PotionEffectType potion = null;
        int ticks = 0;
        int amplifier = 0;
        double value = number(m.get("valor"), 0, "valor");
        String text = m.get("texto") == null ? null : String.valueOf(m.get("texto"));
        EntityType entity = null;
        switch (type) {
            case "efeito" -> {
                potion = Registry.EFFECT.get(namespaced(String.valueOf(m.get("efeito"))));
                if (potion == null) {
                    throw new IllegalArgumentException("ação efeito: efeito inválido '" + m.get("efeito") + "'");
                }
                double seconds = number(m.get("duracao"), 10, "duracao");
                double level = number(m.get("nivel"), 1, "nivel");
                if (seconds <= 0 || seconds > 100000 || level < 1 || level > 255) {
                    throw new IllegalArgumentException("ação efeito: duracao (segundos) ou nivel (1-255) inválidos");
                }
                ticks = (int) Math.round(seconds * 20);
                amplifier = (int) level - 1;
            }
            case "curar", "dano" -> {
                if (value <= 0 || value > 1000) {
                    throw new IllegalArgumentException("ação " + type + ": 'valor' deve ser de 0 a 1000");
                }
            }
            case "comando" -> {
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("ação comando: falta 'texto' (use %jogador% para o nome)");
                }
            }
            case "som" -> {
                if (text == null || NamespacedKey.fromString(text.toLowerCase(Locale.ROOT)) == null) {
                    throw new IllegalArgumentException("ação som: 'texto' deve ser um som (ex.: entity.player.levelup)");
                }
                if (value == 0) {
                    value = 1;
                }
            }
            case "projetil" -> {
                String name = String.valueOf(m.get("entidade"));
                for (EntityType candidate : EntityType.values()) {
                    if (candidate != EntityType.UNKNOWN && candidate.name().equalsIgnoreCase(name)) {
                        entity = candidate;
                    }
                }
                if (entity == null || entity.getEntityClass() == null
                        || !org.bukkit.entity.Projectile.class.isAssignableFrom(entity.getEntityClass())) {
                    throw new IllegalArgumentException("ação projetil: entidade inválida '" + name
                            + "' (ex.: SNOWBALL, ARROW, SMALL_FIREBALL, WIND_CHARGE)");
                }
                if (value == 0) {
                    value = 1.5;
                }
                if (value < 0 || value > 10) {
                    throw new IllegalArgumentException("ação projetil: 'valor' (velocidade) deve ser de 0 a 10");
                }
            }
            case "impulso" -> {
                if (value == 0 || Math.abs(value) > 5) {
                    throw new IllegalArgumentException("ação impulso: 'valor' deve ser diferente de 0 e até 5");
                }
            }
            default -> { }
        }
        if (onTarget && (type.equals("projetil") || type.equals("impulso") || type.equals("som"))) {
            throw new IllegalArgumentException("ação " + type + " só vale para o jogador");
        }
        if ((type.equals("dano") || type.equals("raio")) && !onTarget) {
            throw new IllegalArgumentException("ação " + type + ": use em: alvo");
        }
        return new AbilitySpec.Action(type, potion, ticks, amplifier, value, text, entity, onTarget);
    }

    private static final Map<String, Integer> COOK_DEFAULT_TIME = Map.of("furnace", 200, "blasting", 100,
            "smoking", 100, "campfire", 600);

    /** {@code cozimento:} lista de {tipo, entrada, xp, tempo, quantidade}. */
    private static List<CookSpec> parseCooking(ConfigurationSection s) {
        List<CookSpec> specs = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("cozimento")) {
            String kind = raw.get("tipo") == null ? "furnace" : String.valueOf(raw.get("tipo")).toLowerCase(Locale.ROOT);
            if (!COOK_DEFAULT_TIME.containsKey(kind)) {
                throw new IllegalArgumentException("cozimento: tipo inválido '" + kind
                        + "' (furnace, blasting, smoking ou campfire)");
            }
            Object input = raw.get("entrada");
            if (input == null || String.valueOf(input).isBlank()) {
                throw new IllegalArgumentException("cozimento sem 'entrada'");
            }
            try {
                float xp = raw.get("xp") == null ? 0f : Float.parseFloat(String.valueOf(raw.get("xp")));
                int time = raw.get("tempo") == null ? COOK_DEFAULT_TIME.get(kind) : Integer.parseInt(String.valueOf(raw.get("tempo")));
                int amount = raw.get("quantidade") == null ? 1 : Integer.parseInt(String.valueOf(raw.get("quantidade")));
                if (xp < 0 || time < 1 || amount < 1 || amount > 64) {
                    throw new NumberFormatException();
                }
                specs.add(new CookSpec(kind, String.valueOf(input).strip(), xp, time, amount));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("cozimento: xp, tempo (ticks) ou quantidade (1-64) inválidos");
            }
        }
        return List.copyOf(specs);
    }

    private static String parseModel(ConfigurationSection s) {
        String model = s.getString("modelo");
        if (model == null || model.isBlank()) {
            return null;
        }
        if (!CreationRegistry.isValidId(model)) {
            throw new IllegalArgumentException("modelo inválido '" + model + "' (use minúsculas, números e _)");
        }
        return model;
    }

    private static List<AttributeSpec> parseAttributes(ConfigurationSection s) {
        List<AttributeSpec> specs = new ArrayList<>();
        for (Map<?, ?> raw : s.getMapList("atributos")) {
            String name = String.valueOf(raw.get("atributo"));
            Attribute attribute = Registry.ATTRIBUTE.get(namespaced(name));
            if (attribute == null) {
                throw new IllegalArgumentException("atributo inválido '" + name + "'");
            }
            double value;
            try {
                value = Double.parseDouble(String.valueOf(raw.get("valor")));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("valor inválido em '" + name + "'");
            }
            String operationName = raw.get("operacao") == null ? "add_number" : String.valueOf(raw.get("operacao"));
            AttributeModifier.Operation operation = switch (operationName.toLowerCase(Locale.ROOT)) {
                case "add_number" -> AttributeModifier.Operation.ADD_NUMBER;
                case "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
                case "multiply_scalar_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                default -> throw new IllegalArgumentException("operação inválida '" + operationName
                        + "' (add_number, add_scalar ou multiply_scalar_1)");
            };
            String slotName = raw.get("slot") == null ? "any" : String.valueOf(raw.get("slot"));
            EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(slotName.toLowerCase(Locale.ROOT));
            if (slot == null) {
                throw new IllegalArgumentException("slot inválido '" + slotName + "'");
            }
            specs.add(new AttributeSpec(attribute, value, operation, slot));
        }
        return List.copyOf(specs);
    }

    private static Map<Enchantment, Integer> parseEnchantments(ConfigurationSection s) {
        Map<Enchantment, Integer> result = new LinkedHashMap<>();
        if (s == null) {
            return result;
        }
        for (String name : s.getKeys(false)) {
            Enchantment enchantment = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT).get(namespaced(name));
            if (enchantment == null) {
                throw new IllegalArgumentException("encantamento inválido '" + name + "'");
            }
            int level = s.getInt(name, 0);
            if (level < 1 || level > 255) {
                throw new IllegalArgumentException("nível inválido em '" + name + "' (1 a 255)");
            }
            result.put(enchantment, level);
        }
        return result;
    }

    private static NamespacedKey namespaced(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? NamespacedKey.minecraft("invalido") : key;
    }

    // ── Receitas ────────────────────────────────────────────────

    private static RecipeSpec parseRecipe(ConfigurationSection s) {
        ConfigurationSection r = s.getConfigurationSection("receita");
        if (r == null) {
            return null;
        }
        String type = r.getString("tipo", "shaped").toLowerCase(Locale.ROOT);
        int amount = r.getInt("quantidade", 1);
        if (amount < 1 || amount > 64) {
            throw new IllegalArgumentException("receita: quantidade inválida (1 a 64)");
        }
        switch (type) {
            case "shaped" -> {
                ConfigurationSection map = r.getConfigurationSection("ingredientes");
                if (map == null) {
                    throw new IllegalArgumentException("receita: faltam os ingredientes");
                }
                Map<Character, String> ingredients = new LinkedHashMap<>();
                for (String key : map.getKeys(false)) {
                    if (key.length() != 1) {
                        throw new IllegalArgumentException("receita: a chave '" + key + "' deve ser 1 caractere");
                    }
                    ingredients.put(key.charAt(0), map.getString(key, ""));
                }
                return new RecipeSpec(true, List.copyOf(r.getStringList("forma")), ingredients, List.of(), amount);
            }
            case "shapeless" -> {
                return new RecipeSpec(false, List.of(), Map.of(), List.copyOf(r.getStringList("ingredientes")), amount);
            }
            default -> throw new IllegalArgumentException("receita: tipo inválido '" + type + "' (shaped ou shapeless)");
        }
    }

    private NamespacedKey recipeKey(ItemSource item) {
        String prefix = item.type() == CreationType.BLOCK ? "receita_bloco_" : "receita_";
        return new NamespacedKey(plugin, prefix + item.id());
    }

    private Recipe buildRecipe(ItemSource item) {
        RecipeSpec spec = item.recipe();
        var result = item.create(plugin, spec.amount());
        NamespacedKey key = recipeKey(item);
        if (spec.shaped()) {
            if (spec.shape().isEmpty() || spec.shape().size() > 3
                    || spec.shape().stream().anyMatch(row -> row.isEmpty() || row.length() > 3)) {
                throw new IllegalArgumentException("a forma deve ter de 1 a 3 linhas de 1 a 3 caracteres");
            }
            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape(spec.shape().toArray(String[]::new));
            for (var entry : spec.ingredients().entrySet()) {
                if (spec.shape().stream().noneMatch(row -> row.indexOf(entry.getKey()) >= 0)) {
                    throw new IllegalArgumentException("'" + entry.getKey() + "' não aparece na forma");
                }
                recipe.setIngredient(entry.getKey(), choice(entry.getValue()));
            }
            for (String row : spec.shape()) {
                for (char c : row.toCharArray()) {
                    if (c != ' ' && !spec.ingredients().containsKey(c)) {
                        throw new IllegalArgumentException("falta o ingrediente '" + c + "'");
                    }
                }
            }
            return recipe;
        }
        if (spec.loose().isEmpty() || spec.loose().size() > 9) {
            throw new IllegalArgumentException("receita sem forma precisa de 1 a 9 ingredientes");
        }
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (String ingredient : spec.loose()) {
            recipe.addIngredient(choice(ingredient));
        }
        return recipe;
    }

    /** Material vanilla ({@code EMERALD}) ou outro item/bloco do plugin ({@code cc:<id>}). */
    private RecipeChoice choice(String ingredient) {
        ItemStack stack = resolveStack(ingredient, 1);
        if (stack == null) {
            throw new IllegalArgumentException("ingrediente inválido '" + ingredient + "'");
        }
        return ingredient.regionMatches(true, 0, "cc:", 0, 3)
                ? RecipeChoice.exactChoice(stack)
                : new RecipeChoice.MaterialChoice(stack.getType());
    }

    /**
     * Cria a pilha de um material vanilla ({@code DIAMOND}) ou de um item/bloco do plugin ({@code cc:<id>}).
     *
     * @return a pilha, ou {@code null} se não existir
     */
    public ItemStack resolveStack(String spec, int amount) {
        if (spec.regionMatches(true, 0, "cc:", 0, 3)) {
            String id = spec.substring(3).toLowerCase(Locale.ROOT);
            var found = registry.get(CreationType.ITEM, id).or(() -> registry.get(CreationType.BLOCK, id));
            return found.map(creation -> ((ItemSource) creation).create(plugin, amount)).orElse(null);
        }
        Material material = Material.matchMaterial(spec);
        if (material == null || !material.isItem() || material.isAir()) {
            return null;
        }
        return new ItemStack(material, Math.max(1, amount));
    }

    private void warn(File file, String id, String reason) {
        plugin.getLogger().warning(file.getParentFile().getName() + "/" + file.getName() + ": '" + id
                + "' ignorado (" + reason + ").");
    }
}
