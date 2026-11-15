package dev.creatorcore.model;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.mob.MobDefinition;
import dev.creatorcore.registry.CreationType;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Mobs com {@code modelo:} ficam invisíveis e recebem um item display com o modelo importado, que
 * acompanha a posição e a rotação do mob a cada tick. O display não é salvo no mundo: é recriado
 * quando o mob carrega (chunk ou reinício).
 */
public final class ModelMobs implements Listener {

    private final CreatorCorePlugin plugin;
    private final Map<UUID, ItemDisplay> displays = new HashMap<>();
    /** Giro extra do modelo (Blockbench desenha a frente voltada para o norte; o mob olha para o sul). */
    private final Map<UUID, Double> yawOffsets = new HashMap<>();
    private BukkitTask task;

    public ModelMobs(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        refreshAll();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        displays.values().forEach(Entity::remove);
        displays.clear();
    }

    /** Recria os displays de todos os mobs carregados (após recarregar ou iniciar). */
    public void refreshAll() {
        displays.values().forEach(Entity::remove);
        displays.clear();
        yawOffsets.clear();
        for (var world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                attachIfCustom(living);
            }
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) {
                attachIfCustom(living);
            }
        }
    }

    private void attachIfCustom(LivingEntity living) {
        String id = living.getPersistentDataContainer().get(plugin.mobKey(), PersistentDataType.STRING);
        if (id != null && plugin.registry().get(CreationType.MOB, id).orElse(null) instanceof MobDefinition def) {
            attach(living, def);
        }
    }

    public void attach(LivingEntity mob, MobDefinition def) {
        if (def.model() == null || !plugin.models().has(def.model())) {
            return;
        }
        ItemDisplay existing = displays.get(mob.getUniqueId());
        if (existing != null && existing.isValid()) {
            return;
        }
        mob.setInvisible(true);
        ItemStack stack = new ItemStack(Material.STICK);
        var meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey(ModelManager.NAMESPACE, def.model()));
        stack.setItemMeta(meta);
        float scale = (float) def.modelScale();
        Location where = mob.getLocation();
        ItemDisplay display = mob.getWorld().spawn(where, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(0, 0.5f * scale + (float) def.modelOffsetY(), 0),
                    new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setTeleportDuration(2);
            d.setPersistent(false);
        });
        displays.put(mob.getUniqueId(), display);
        yawOffsets.put(mob.getUniqueId(), def.modelYaw());
    }

    public void detach(UUID mobId) {
        ItemDisplay display = displays.remove(mobId);
        yawOffsets.remove(mobId);
        if (display != null) {
            display.remove();
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, ItemDisplay>> it = displays.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Entity mob = Bukkit.getEntity(entry.getKey());
            ItemDisplay display = entry.getValue();
            if (mob == null || !mob.isValid() || mob.isDead() || !display.isValid()) {
                display.remove();
                yawOffsets.remove(entry.getKey());
                it.remove();
                continue;
            }
            Location where = mob.getLocation();
            where.setYaw((float) (where.getYaw() + yawOffsets.getOrDefault(entry.getKey(), 180.0)));
            display.teleport(where);
        }
    }
}
