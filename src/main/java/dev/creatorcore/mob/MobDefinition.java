package dev.creatorcore.mob;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.block.DropSpec;
import dev.creatorcore.registry.Creation;
import dev.creatorcore.registry.CreationType;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Definição de um mob customizado, lida de {@code plugins/CreatorCore/mobs/*.yml}: um mob vanilla
 * ({@code entity}) com nome, atributos, equipamento e drops próprios, marcado com {@code creatorcore:mob}.
 *
 * @param attributes valores base dos atributos
 * @param equipment  item (material ou {@code cc:<id>}) por slot
 * @param drops      drops próprios
 * @param keepVanillaDrops mantém também os drops vanilla da espécie
 * @param xp         experiência ao morrer (-1 = a vanilla)
 */
public record MobDefinition(String id, EntityType entity, String name, Map<Attribute, Double> attributes,
                            Map<EquipmentSlot, String> equipment, List<DropSpec> drops, boolean keepVanillaDrops,
                            int xp, boolean glowing, boolean silent) implements Creation {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public CreationType type() {
        return CreationType.MOB;
    }

    /** Invoca o mob em {@code where}. */
    public LivingEntity spawn(CreatorCorePlugin plugin, Location where) {
        return (LivingEntity) where.getWorld().spawnEntity(where, entity, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
            LivingEntity mob = (LivingEntity) spawned;
            mob.getPersistentDataContainer().set(plugin.mobKey(), PersistentDataType.STRING, id);
            if (name != null && !name.isBlank()) {
                mob.customName(MM.deserialize(name));
                mob.setCustomNameVisible(true);
            }
            for (var entry : attributes.entrySet()) {
                AttributeInstance instance = mob.getAttribute(entry.getKey());
                if (instance != null) {
                    instance.setBaseValue(entry.getValue());
                }
            }
            AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                mob.setHealth(health.getValue());
            }
            for (var entry : equipment.entrySet()) {
                ItemStack stack = plugin.itemLoader().resolveStack(entry.getValue(), 1);
                if (stack != null && mob.getEquipment() != null) {
                    mob.getEquipment().setItem(entry.getKey(), stack, true);
                    mob.getEquipment().setDropChance(entry.getKey(), 0f);
                }
            }
            mob.setGlowing(glowing);
            mob.setSilent(silent);
            mob.setRemoveWhenFarAway(false);
            if (mob instanceof Mob m) {
                m.setPersistent(true);
            }
        });
    }
}
