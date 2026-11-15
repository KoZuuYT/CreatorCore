package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

/** Executa as habilidades ({@code habilidades:}) dos itens do plugin. */
public final class AbilityListener implements Listener {

    private final CreatorCorePlugin plugin;
    /** jogador -> (item#índice da habilidade -> instante em que a recarga acaba, em ms). */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public AbilityListener(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (event.getHand() != EquipmentSlot.HAND || action == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        // Com Shift apertado vale só sneak_right_click; sem Shift, só right_click.
        String trigger = action.isLeftClick() ? "left_click" : player.isSneaking() ? "sneak_right_click" : "right_click";
        run(player, event.getItem(), trigger, null);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            run(player, player.getInventory().getItemInMainHand(), "hit", event.getEntity());
        }
    }

    private void run(Player player, ItemStack stack, String trigger, Entity hit) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return;
        }
        String id = stack.getItemMeta().getPersistentDataContainer().get(plugin.idKey(), PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        var found = plugin.registry().get(CreationType.ITEM, id);
        if (found.isEmpty() || !(found.get() instanceof ItemDefinition item)) {
            return;
        }
        for (int i = 0; i < item.abilities().size(); i++) {
            AbilitySpec ability = item.abilities().get(i);
            if (!ability.trigger().equals(trigger) || onCooldown(player, id + "#" + i, ability)) {
                continue;
            }
            Entity target = hit != null ? hit : lookedAt(player);
            for (AbilitySpec.Action action : ability.actions()) {
                AbilityActions.apply(player, target, action);
            }
            if (ability.consume() && player.getGameMode() != GameMode.CREATIVE) {
                stack.setAmount(stack.getAmount() - 1);
            }
            return; // uma habilidade por clique
        }
    }

    /** @return true se ainda em recarga (e avisa); senão inicia a recarga. */
    private boolean onCooldown(Player player, String key, AbilitySpec ability) {
        if (ability.cooldown() <= 0) {
            return false;
        }
        var map = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        long until = map.getOrDefault(key, 0L);
        if (until > now) {
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<red>Em recarga: <white>"
                    + String.format(Locale.ROOT, "%.1f", (until - now) / 1000.0) + "s"));
            return true;
        }
        map.put(key, now + (long) (ability.cooldown() * 1000));
        return false;
    }

    private Entity lookedAt(Player player) {
        RayTraceResult hit = player.getWorld().rayTraceEntities(player.getEyeLocation(),
                player.getEyeLocation().getDirection(), 20, 0.3, e -> e != player && e instanceof LivingEntity);
        return hit == null ? null : hit.getHitEntity();
    }
}
