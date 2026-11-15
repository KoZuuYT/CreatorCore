package dev.creatorcore.block;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.ItemSource;
import dev.creatorcore.item.RecipeSpec;
import dev.creatorcore.registry.CreationType;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Definição de um bloco customizado, lida de {@code plugins/CreatorCore/blocks/*.yml}.
 *
 * @param model        nome do modelo em blocks.json / resource pack
 * @param state        índice do estado do note block que representa este bloco
 * @param hardness     dureza (0 = quebra instantânea; negativo = indestrutível em sobrevivência)
 * @param tool         ferramenta que acelera a quebra ({@code NONE} = nenhuma)
 * @param tier         nível mínimo da ferramenta para dropar (só vale com {@code requiresTool})
 * @param requiresTool sem a ferramenta certa o bloco quebra mais devagar e não dropa nada
 * @param drops        drops; vazio = o próprio bloco
 */
public record BlockDefinition(String id, String name, List<String> lore, String model, int state, double hardness,
                              ToolInfo.Type tool, int tier, boolean requiresTool, List<DropSpec> drops,
                              RecipeSpec recipe, List<dev.creatorcore.item.CookSpec> cooking,
                              BlockExtras extras) implements ItemSource {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public CreationType type() {
        return CreationType.BLOCK;
    }

    /** O item que coloca este bloco (um note block com o id gravado). */
    @Override
    public ItemStack create(CreatorCorePlugin plugin, int amount) {
        ItemStack stack = new ItemStack(Material.NOTE_BLOCK, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(MM.deserialize(name == null || name.isBlank() ? id : name)));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(line -> noItalic(MM.deserialize(line))).toList());
        }
        CustomModelDataComponent data = meta.getCustomModelDataComponent();
        data.setStrings(List.of(model));
        meta.setCustomModelDataComponent(data);
        meta.getPersistentDataContainer().set(plugin.idKey(), PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(plugin.blockKey(), PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    // ── Mineração ───────────────────────────────────────────────

    private boolean typeMatches(ToolInfo info) {
        return tool != ToolInfo.Type.NONE && info.type() == tool;
    }

    /** A ferramenta na mão permite dropar? Sem {@code requiresTool}, sempre. */
    public boolean canHarvest(ItemStack held) {
        if (!requiresTool) {
            return true;
        }
        ToolInfo info = ToolInfo.of(held.getType());
        return typeMatches(info) && info.tier() >= tier;
    }

    /**
     * Progresso de quebra por tick (1.0 = quebrado), na fórmula do jogo: velocidade da ferramenta
     * (+ Eficiência) ÷ dureza ÷ 30 (ou ÷ 100 sem a ferramenta certa). Ignora Pressa/Fadiga e água.
     */
    public double damagePerTick(ItemStack held, boolean onGround) {
        if (hardness < 0) {
            return 0;
        }
        ToolInfo info = ToolInfo.of(held.getType());
        double speed = 1;
        if (typeMatches(info)) {
            speed = info.speed();
            int efficiency = held.getEnchantmentLevel(Enchantment.EFFICIENCY);
            if (speed > 1 && efficiency > 0) {
                speed += efficiency * efficiency + 1;
            }
        }
        if (!onGround) {
            speed /= 5;
        }
        return speed / hardness / (canHarvest(held) ? 30 : 100);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
