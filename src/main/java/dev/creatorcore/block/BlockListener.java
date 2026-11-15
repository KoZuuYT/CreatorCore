package dev.creatorcore.block;

import dev.creatorcore.CreatorCorePlugin;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Comportamento dos blocos customizados (note blocks em estados reservados): colocar, quebrar/dropar,
 * e blindagem contra os comportamentos vanilla do note block (trocar nota, tocar, atualizar instrumento).
 */
public final class BlockListener implements Listener {

    private final CreatorCorePlugin plugin;
    private final CustomBlocks blocks;
    private final MiningManager mining;

    public BlockListener(CreatorCorePlugin plugin, CustomBlocks blocks, MiningManager mining) {
        this.plugin = plugin;
        this.blocks = blocks;
        this.mining = mining;
    }

    // ── Colocar ─────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.NOTE_BLOCK || !item.hasItemMeta()) {
            return;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(plugin.blockKey(), PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        var found = plugin.registry().get(dev.creatorcore.registry.CreationType.BLOCK, id);
        if (found.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        BlockDefinition definition = (BlockDefinition) found.get();
        // Sem atualizar vizinhos: o estado do note block não pode ser recalculado pelo jogo.
        event.getBlockPlaced().setBlockData(blocks.states().data(definition.state()), false);
    }

    // ── Quebrar e dropar ────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BlockDefinition definition = blocks.at(event.getBlock());
        if (definition == null) {
            return;
        }
        Player player = event.getPlayer();
        mining.stop(player);
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (player.getGameMode() == GameMode.CREATIVE || !definition.canHarvest(player.getInventory().getItemInMainHand())) {
            return;
        }
        drop(definition, event.getBlock());
    }

    private void drop(BlockDefinition definition, Block block) {
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        if (definition.drops().isEmpty()) {
            dropStack(at, definition.create(plugin, 1));
            return;
        }
        for (DropSpec drop : definition.drops()) {
            int amount = ThreadLocalRandom.current().nextInt(drop.min(), drop.max() + 1);
            ItemStack stack = amount > 0 ? plugin.itemLoader().resolveStack(drop.item(), amount) : null;
            if (stack != null) {
                dropStack(at, stack);
            }
        }
    }

    private static void dropStack(Location at, ItemStack stack) {
        int remaining = stack.getAmount();
        int max = stack.getMaxStackSize();
        while (remaining > 0) {
            ItemStack part = stack.clone();
            part.setAmount(Math.min(max, remaining));
            remaining -= part.getAmount();
            at.getWorld().dropItemNaturally(at, part);
        }
    }

    // ── Explosões: o bloco some e dropa só se não exigir ferramenta ──

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> list) {
        for (Iterator<Block> it = list.iterator(); it.hasNext();) {
            Block block = it.next();
            BlockDefinition definition = blocks.at(block);
            if (definition == null) {
                continue;
            }
            it.remove();
            block.setType(Material.AIR);
            if (!definition.requiresTool()) {
                drop(definition, block);
            }
        }
    }

    // ── Blindagem contra o comportamento vanilla do note block ──

    /** O clique direito não troca a nota; colocar blocos contra ele continua funcionando. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && blocks.at(event.getClickedBlock()) != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
            // Sem isto o servidor trata o clique como "consumido" e não deixa colocar um bloco apoiado neste.
            event.setUseItemInHand(Event.Result.ALLOW);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        if (blocks.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Impede que vizinhos/redstone mudem instrumento ou powered (o que trocaria o bloco). */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (event.getBlock().getType() == Material.NOTE_BLOCK && blocks.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Pistões não movem blocos customizados (o estado poderia se perder). */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> blocks.at(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> blocks.at(b) != null)) {
            event.setCancelled(true);
        }
    }
}
