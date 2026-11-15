package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationRegistry;
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
    public record LoadResult(int items, int recipes) {
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

    /** Recarrega itens e receitas. Entradas inválidas são ignoradas com aviso. */
    public LoadResult load() {
        registry.clear(CreationType.ITEM);
        unregisterRecipes();
        File dir = new File(plugin.getDataFolder(), "items");
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return new LoadResult(0, 0);
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
                    registry.register(parse(id, section));
                    loaded++;
                } catch (IllegalArgumentException e) {
                    warn(file, id, e.getMessage());
                }
            }
        }
        // Receitas só depois de todos os itens, pois um ingrediente pode ser outro item (cc:<id>).
        int recipes = 0;
        for (var creation : registry.all(CreationType.ITEM)) {
            ItemDefinition item = (ItemDefinition) creation;
            if (item.recipe() == null) {
                continue;
            }
            try {
                Recipe recipe = buildRecipe(item);
                NamespacedKey key = recipeKey(item);
                if (!Bukkit.addRecipe(recipe)) {
                    throw new IllegalArgumentException("o servidor recusou a receita");
                }
                recipeKeys.add(key);
                recipes++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Receita de '" + item.id() + "' ignorada (" + e.getMessage() + ").");
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            discoverFor(online);
        }
        return new LoadResult(loaded, recipes);
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
                s.getBoolean("inquebravel", false), s.getBoolean("brilho", false), parseRecipe(s));
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

    private NamespacedKey recipeKey(ItemDefinition item) {
        return new NamespacedKey(plugin, "receita_" + item.id());
    }

    private Recipe buildRecipe(ItemDefinition item) {
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

    /** Material vanilla ({@code EMERALD}) ou outro item do plugin ({@code cc:<id>}). */
    private RecipeChoice choice(String ingredient) {
        if (ingredient.regionMatches(true, 0, "cc:", 0, 3)) {
            String id = ingredient.substring(3).toLowerCase(Locale.ROOT);
            var found = registry.get(CreationType.ITEM, id);
            if (found.isEmpty()) {
                throw new IllegalArgumentException("ingrediente 'cc:" + id + "' não existe");
            }
            return RecipeChoice.exactChoice(((ItemDefinition) found.get()).create(plugin, 1));
        }
        Material material = Material.matchMaterial(ingredient);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("ingrediente inválido '" + ingredient + "'");
        }
        return new RecipeChoice.MaterialChoice(material);
    }

    private void warn(File file, String id, String reason) {
        plugin.getLogger().warning("items/" + file.getName() + ": '" + id + "' ignorado (" + reason + ").");
    }
}
