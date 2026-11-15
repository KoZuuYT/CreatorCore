package dev.creatorcore.item;

import dev.creatorcore.registry.Creation;
import dev.creatorcore.registry.CreationType;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Definição de um item customizado, lida de {@code plugins/CreatorCore/items/*.yml}.
 * O item criado carrega o id em {@code creatorcore:id} para ser reconhecido depois.
 */
public record ItemDefinition(String id, Material material, String name, List<String> lore) implements Creation {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public CreationType type() {
        return CreationType.ITEM;
    }

    public ItemStack create(NamespacedKey idKey, int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (name != null && !name.isBlank()) {
            meta.displayName(noItalic(MM.deserialize(name)));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(line -> noItalic(MM.deserialize(line))).toList());
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
