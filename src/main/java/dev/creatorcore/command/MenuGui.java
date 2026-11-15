package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.ItemSource;
import dev.creatorcore.mob.MobDefinition;
import dev.creatorcore.registry.Creation;
import dev.creatorcore.registry.CreationType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Menu {@code /cc menu}: pega itens e blocos e invoca mobs clicando. */
public final class MenuGui implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45;
    private static final int TAB_ITEMS = 47;
    private static final int TAB_BLOCKS = 48;
    private static final int TAB_MOBS = 49;
    private static final int PAGE_INFO = 51;
    private static final int NEXT = 53;
    private static final String PERM = "creatorcore.admin";

    private final CreatorCorePlugin plugin;

    public MenuGui(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Dono do inventário: guarda a aba e a página abertas. */
    private static final class Holder implements InventoryHolder {
        CreationType tab;
        int page;
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void open(Player player, CreationType tab, int page) {
        Holder holder = new Holder();
        holder.tab = tab;
        List<Creation> entries = new ArrayList<>(plugin.registry().all(tab));
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        holder.page = Math.max(0, Math.min(page, pages - 1));
        String title = switch (tab) {
            case ITEM -> "Itens";
            case BLOCK -> "Blocos";
            case MOB -> "Mobs";
        };
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("CreatorCore - " + title));
        holder.inventory = inv;
        int start = holder.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
            inv.setItem(i, icon(entries.get(start + i)));
        }
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int slot = PAGE_SIZE; slot < 54; slot++) {
            inv.setItem(slot, filler);
        }
        if (holder.page > 0) {
            inv.setItem(PREV, button(Material.ARROW, "<yellow>Página anterior", null));
        }
        if (holder.page < pages - 1) {
            inv.setItem(NEXT, button(Material.ARROW, "<yellow>Próxima página", null));
        }
        inv.setItem(TAB_ITEMS, tabButton(Material.DIAMOND, "Itens", tab == CreationType.ITEM));
        inv.setItem(TAB_BLOCKS, tabButton(Material.GRASS_BLOCK, "Blocos", tab == CreationType.BLOCK));
        inv.setItem(TAB_MOBS, tabButton(Material.ZOMBIE_SPAWN_EGG, "Mobs", tab == CreationType.MOB));
        inv.setItem(PAGE_INFO, button(Material.PAPER, "<gray>Página <white>" + (holder.page + 1) + "/" + pages, null));
        player.openInventory(inv);
    }

    private ItemStack tabButton(Material material, String name, boolean active) {
        return button(material, (active ? "<green>" : "<gray>") + name, active ? List.of("<dark_gray>Aba atual") : null);
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        if (lore != null) {
            meta.lore(lore.stream().map(l -> MM.deserialize(l).decoration(TextDecoration.ITALIC, false)).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack icon(Creation creation) {
        ItemStack stack;
        List<String> hint;
        if (creation instanceof MobDefinition mob) {
            Material egg = Material.matchMaterial(mob.entity().name() + "_SPAWN_EGG");
            stack = new ItemStack(egg == null ? Material.PAPER : egg);
            ItemMeta meta = stack.getItemMeta();
            String shown = mob.name() == null || mob.name().isBlank() ? mob.id() : mob.name();
            meta.displayName(MM.deserialize(shown).decoration(TextDecoration.ITALIC, false));
            stack.setItemMeta(meta);
            hint = List.of("<dark_gray>" + mob.id(), "<yellow>Clique: invocar 1", "<yellow>Shift+clique: invocar 5");
        } else {
            stack = ((ItemSource) creation).create(plugin, 1);
            hint = List.of("<dark_gray>" + creation.id(), "<yellow>Clique: pegar 1", "<yellow>Shift+clique: pegar 64");
        }
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        for (String line : hint) {
            lore.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission(PERM)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        switch (slot) {
            case PREV -> open(player, holder.tab, holder.page - 1);
            case NEXT -> open(player, holder.tab, holder.page + 1);
            case TAB_ITEMS -> open(player, CreationType.ITEM, 0);
            case TAB_BLOCKS -> open(player, CreationType.BLOCK, 0);
            case TAB_MOBS -> open(player, CreationType.MOB, 0);
            default -> {
                int index = holder.page * PAGE_SIZE + slot;
                var entries = new ArrayList<>(plugin.registry().all(holder.tab));
                if (slot < PAGE_SIZE && index < entries.size()) {
                    choose(player, entries.get(index), event.isShiftClick());
                }
            }
        }
    }

    private void choose(Player player, Creation creation, boolean big) {
        if (creation instanceof MobDefinition mob) {
            var target = player.getTargetBlockExact(20);
            var where = target == null ? player.getLocation() : target.getLocation().add(0.5, 1, 0.5);
            int amount = big ? 5 : 1;
            player.closeInventory();
            for (int i = 0; i < amount; i++) {
                mob.spawn(plugin, where);
            }
            player.sendMessage(MM.deserialize("<gold>[CreatorCore]</gold> <green>Invocado " + amount + "x " + mob.id() + "."));
            return;
        }
        ItemSource source = (ItemSource) creation;
        ItemStack stack = source.create(plugin, big ? source.create(plugin, 1).getMaxStackSize() : 1);
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
