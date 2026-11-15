package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationRegistry;
import dev.creatorcore.registry.CreationType;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Carrega os itens de {@code plugins/CreatorCore/items/*.yml}. Cada arquivo pode ter vários itens,
 * um por chave de nível superior:
 * <pre>
 * espada_teste:
 *   material: DIAMOND_SWORD
 *   nome: "&lt;gold&gt;Espada de Teste"
 *   lore: ["&lt;gray&gt;Primeira criação"]
 * </pre>
 */
public final class ItemLoader {

    private final CreatorCorePlugin plugin;
    private final CreationRegistry registry;

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

    /** Recarrega todos os itens. Entradas inválidas são ignoradas com aviso. @return quantos itens foram carregados */
    public int load() {
        registry.clear(CreationType.ITEM);
        File dir = new File(plugin.getDataFolder(), "items");
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
                    registry.register(parse(id, section));
                    loaded++;
                } catch (IllegalArgumentException e) {
                    warn(file, id, e.getMessage());
                }
            }
        }
        return loaded;
    }

    private static ItemDefinition parse(String id, ConfigurationSection s) {
        String materialName = s.getString("material", "");
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("material inválido '" + materialName + "'");
        }
        List<String> lore = s.getStringList("lore");
        return new ItemDefinition(id, material, s.getString("nome", ""), List.copyOf(lore));
    }

    private void warn(File file, String id, String reason) {
        plugin.getLogger().warning("items/" + file.getName() + ": '" + id + "' ignorado (" + reason + ").");
    }
}
