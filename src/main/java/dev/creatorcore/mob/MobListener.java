package dev.creatorcore.mob;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.block.DropSpec;
import dev.creatorcore.registry.CreationType;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Aplica os drops e a experiência próprios dos mobs customizados. */
public final class MobListener implements Listener {

    private final CreatorCorePlugin plugin;

    public MobListener(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** Spawn natural: troca o mob vanilla de base pelo customizado, conforme a chance e as regras do mob. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        var entity = event.getEntity();
        if (entity.getPersistentDataContainer().has(plugin.mobKey(), PersistentDataType.STRING)) {
            return;
        }
        var location = entity.getLocation();
        for (var creation : plugin.registry().all(CreationType.MOB)) {
            MobDefinition mob = (MobDefinition) creation;
            SpawnSpec spawn = mob.spawn();
            if (spawn == null || mob.entity() != entity.getType()
                    || !spawn.worlds().isEmpty() && !spawn.worlds().contains(location.getWorld().getName())
                    || !spawn.biomes().isEmpty()
                            && !spawn.biomes().contains(location.getBlock().getBiome().getKey().getKey())
                    || ThreadLocalRandom.current().nextDouble() >= spawn.chance()
                    || countAlive(location.getWorld(), mob.id()) >= spawn.limit()) {
                continue;
            }
            event.setCancelled(true);
            var where = location.clone();
            plugin.getServer().getScheduler().runTask(plugin, () -> mob.spawn(plugin, where));
            return;
        }
    }

    private long countAlive(org.bukkit.World world, String id) {
        return world.getLivingEntities().stream()
                .filter(e -> id.equals(e.getPersistentDataContainer().get(plugin.mobKey(), PersistentDataType.STRING)))
                .count();
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(plugin.mobKey(), PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        plugin.modelMobs().detach(event.getEntity().getUniqueId(), event.getEntity().getLocation());
        var found = plugin.registry().get(CreationType.MOB, id);
        if (found.isEmpty()) {
            return; // definição removida: comportamento vanilla
        }
        MobDefinition mob = (MobDefinition) found.get();
        if (!mob.keepVanillaDrops()) {
            event.getDrops().clear();
        }
        boolean byPlayer = event.getEntity().getKiller() != null;
        for (DropSpec drop : mob.drops()) {
            if (drop.onlyPlayer() && !byPlayer
                    || drop.chance() < 1.0 && ThreadLocalRandom.current().nextDouble() >= drop.chance()) {
                continue;
            }
            int amount = ThreadLocalRandom.current().nextInt(drop.min(), drop.max() + 1);
            ItemStack stack = amount > 0 ? plugin.itemLoader().resolveStack(drop.item(), amount) : null;
            if (stack == null) {
                continue;
            }
            int max = stack.getMaxStackSize();
            for (int left = stack.getAmount(); left > 0; left -= max) {
                ItemStack part = stack.clone();
                part.setAmount(Math.min(max, left));
                event.getDrops().add(part);
            }
        }
        if (mob.xp() >= 0) {
            event.setDroppedExp(mob.xp());
        }
        MobExtras extras = mob.extras();
        var where = event.getEntity().getLocation();
        if (extras.deathSound() != null) {
            where.getWorld().playSound(where, extras.deathSound(), 1f, 1f);
        }
        var killer = event.getEntity().getKiller();
        for (String command : extras.deathCommands()) {
            String line = command.replace("%jogador%", killer == null ? "" : killer.getName())
                    .replace("%x%", String.valueOf(where.getBlockX())).replace("%y%", String.valueOf(where.getBlockY()))
                    .replace("%z%", String.valueOf(where.getBlockZ()));
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                    line.startsWith("/") ? line.substring(1) : line);
        }
    }
}
