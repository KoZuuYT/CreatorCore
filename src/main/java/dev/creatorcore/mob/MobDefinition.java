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
 * @param spawn      spawn natural (null = só por /cc invocar)
 * @param animations papel (parado, andando, ataque, morte) -> nome da animação; vazio = detecção automática
 * @param model      modelo importado do Blockbench (models/&lt;id&gt;.bbmodel); null = visual do mob base
 */
public record MobDefinition(String id, EntityType entity, String name, Map<Attribute, Double> attributes,
                            Map<EquipmentSlot, String> equipment, List<DropSpec> drops, boolean keepVanillaDrops,
                            int xp, boolean glowing, boolean silent, SpawnSpec spawn,
                            String model, double modelScale, double modelOffsetY,
                            double modelYaw, Map<String, String> animations,
                            MobBehavior behavior, MobExtras extras) implements Creation {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public CreationType type() {
        return CreationType.MOB;
    }

    /** Invoca o mob em {@code where}. */
    public LivingEntity spawn(CreatorCorePlugin plugin, Location where) {
        LivingEntity result = (LivingEntity) where.getWorld().spawnEntity(where, entity, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
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
            if (!extras.nameVisible()) {
                mob.setCustomNameVisible(false);
            }
            if (extras.baby() && mob instanceof org.bukkit.entity.Ageable ageable) {
                ageable.setBaby();
                ageable.setAgeLock(true);
            }
            if (extras.noAi()) {
                mob.setAI(false);
            }
            if (extras.noGravity()) {
                mob.setGravity(false);
            }
            if (extras.invulnerable()) {
                mob.setInvulnerable(true);
            }
            if (extras.pickup() != null) {
                mob.setCanPickupItems(extras.pickup());
            }
            extras.effects().forEach((type, level) -> mob.addPotionEffect(
                    new org.bukkit.potion.PotionEffect(type, org.bukkit.potion.PotionEffect.INFINITE_DURATION, level - 1)));
        });
        plugin.modelMobs().attach(result, this);
        plugin.behaviors().track(result);
        return result;
    }
}
