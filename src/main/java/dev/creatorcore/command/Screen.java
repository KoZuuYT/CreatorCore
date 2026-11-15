package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Tela de inventário do CreatorCore (menu principal, editor, listas...). Cliques tratados por {@link ScreenListener}. */
public abstract class Screen implements InventoryHolder {

    static final MiniMessage MM = MiniMessage.miniMessage();

    protected final CreatorCorePlugin plugin;
    protected final Inventory inventory;

    protected Screen(CreatorCorePlugin plugin, int rows, String title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, MM.deserialize(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** @param slot posição clicada no inventário da tela, ou {@code -1} se o clique foi no inventário do jogador */
    abstract void onClick(Player player, InventoryClickEvent event, int slot);

    void onClose(Player player) {
    }

    /** Item de botão: nome e linhas de descrição em MiniMessage (sem itálico). */
    static ItemStack icon(Material material, String name, List<String> lore, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        stack.setItemMeta(meta);
        return stack;
    }

    static ItemStack icon(Material material, String name, List<String> lore) {
        return icon(material, name, lore, false);
    }

    static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, List.of(lore), false);
    }

    /** Preenche com vidros cinza os slots vazios. */
    void fillEmpty() {
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    /** Escapa texto de jogador para uso dentro de MiniMessage. */
    static String esc(String text) {
        return MM.escapeTags(text);
    }
}
