package dev.creatorcore.mob;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.item.AbilityActions;
import dev.creatorcore.item.AbilitySpec;
import dev.creatorcore.registry.CreationType;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Comportamento dos mobs customizados ({@code comportamento:}): passivo, domesticação, ataque à distância
 * e habilidades (atacar, ferido, periodica).
 */
public final class MobBehaviors implements Listener {

    private final CreatorCorePlugin plugin;
    private final NamespacedKey ownerKey;
    /** Mobs com comportamento que precisa de verificação periódica (à distância, periódica, domados). */
    private final Map<UUID, LivingEntity> tracked = new HashMap<>();
    /** "uuid#i" (habilidade) ou "uuid>" (disparo) -> instante em que pode agir de novo, em ms. */
    private final Map<String, Long> next = new HashMap<>();
    private BukkitTask task;
    private boolean running;

    public MobBehaviors(CreatorCorePlugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "dono");
    }

    public void start() {
        for (var world : plugin.getServer().getWorlds()) {
            world.getLivingEntities().forEach(this::track);
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10, 10);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        tracked.clear();
    }

    private MobBehavior behaviorOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(plugin.mobKey(), PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        var found = plugin.registry().get(CreationType.MOB, id);
        return found.isPresent() && found.get() instanceof MobDefinition def ? def.behavior() : null;
    }

    /** Passa a acompanhar o mob se o comportamento dele exigir verificação periódica. */
    public void track(LivingEntity entity) {
        MobBehavior b = behaviorOf(entity);
        if (b == null || !(entity instanceof Mob)) {
            return;
        }
        boolean periodic = b.abilities().stream().anyMatch(a -> a.trigger().equals("periodica"));
        if (b.ranged() != null || periodic || b.tame() != null) {
            tracked.put(entity.getUniqueId(), entity);
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        event.getEntities().stream().filter(e -> e instanceof LivingEntity).forEach(e -> track((LivingEntity) e));
    }

    private UUID ownerOf(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean ready(String key, double seconds) {
        long now = System.currentTimeMillis();
        if (next.getOrDefault(key, 0L) > now) {
            return false;
        }
        if (seconds > 0) {
            next.put(key, now + (long) (seconds * 1000));
        }
        return true;
    }

    // ── Alvos: passivo e dono ──

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        MobBehavior b = behaviorOf(event.getEntity());
        if (b == null || event.getTarget() == null) {
            return;
        }
        UUID owner = ownerOf(event.getEntity());
        if (b.passive() || owner != null && owner.equals(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ── Domesticação ──

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof LivingEntity mob)) {
            return;
        }
        MobBehavior b = behaviorOf(mob);
        if (b == null || b.tame() == null || ownerOf(mob) != null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemStack wanted = plugin.itemLoader().resolveStack(b.tame().item(), 1);
        if (wanted == null || held.isEmpty() || !wanted.isSimilar(held)) {
            return;
        }
        event.setCancelled(true);
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        var where = mob.getLocation().add(0, mob.getHeight() + 0.2, 0);
        if (ThreadLocalRandom.current().nextDouble() < b.tame().chance()) {
            mob.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            if (mob instanceof Mob m && m.getTarget() != null && m.getTarget().equals(player)) {
                m.setTarget(null);
            }
            mob.getWorld().spawnParticle(Particle.HEART, where, 7, 0.4, 0.3, 0.4);
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<green>Domado!"));
            track(mob);
        } else {
            mob.getWorld().spawnParticle(Particle.SMOKE, where, 7, 0.4, 0.3, 0.4, 0.02);
            player.sendActionBar(MiniMessage.miniMessage().deserialize("<gray>Não deu certo, tente de novo."));
        }
    }

    // ── Habilidades atacar / ferido ──

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent event) {
        UUID owner = ownerOf(event.getEntity());
        if (owner != null && owner.equals(event.getDamager().getUniqueId())) {
            event.setCancelled(true); // o dono não machuca o próprio mob domado
            return;
        }
        if (running) {
            return; // evita laço quando uma ação "dano" do mob dispara o próprio gatilho
        }
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile p && p.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        if (event.getDamager() instanceof LivingEntity mob) {
            fire(mob, "atacar", event.getEntity());
        }
        if (event.getEntity() instanceof LivingEntity victim) {
            fire(victim, "ferido", attacker);
        }
    }

    private void fire(LivingEntity mob, String trigger, Entity target) {
        MobBehavior b = behaviorOf(mob);
        if (b == null) {
            return;
        }
        for (int i = 0; i < b.abilities().size(); i++) {
            AbilitySpec ability = b.abilities().get(i);
            if (ability.trigger().equals(trigger) && ready(mob.getUniqueId() + "#" + i, ability.cooldown())) {
                run(mob, target, ability);
            }
        }
    }

    private void run(LivingEntity mob, Entity target, AbilitySpec ability) {
        running = true;
        try {
            for (AbilitySpec.Action action : ability.actions()) {
                AbilityActions.apply(mob, target, action);
            }
        } finally {
            running = false;
        }
    }

    // ── Verificação periódica ──

    private void tick() {
        Iterator<Map.Entry<UUID, LivingEntity>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            LivingEntity mob = entry.getValue();
            if (!mob.isValid() || mob.isDead() || !(mob instanceof Mob m)) {
                it.remove();
                next.keySet().removeIf(k -> k.startsWith(entry.getKey().toString()));
                continue;
            }
            MobBehavior b = behaviorOf(mob);
            if (b == null) {
                it.remove();
                continue;
            }
            LivingEntity target = m.getTarget();
            boolean hasTarget = target != null && target.isValid() && target.getWorld().equals(mob.getWorld());
            if (hasTarget && b.ranged() != null) {
                shoot(m, target, b.ranged());
            }
            if (hasTarget) {
                for (int i = 0; i < b.abilities().size(); i++) {
                    AbilitySpec ability = b.abilities().get(i);
                    if (ability.trigger().equals("periodica") && ready(mob.getUniqueId() + "#" + i, ability.cooldown())) {
                        run(mob, target, ability);
                    }
                }
            }
            if (!hasTarget && b.tame() != null) {
                follow(m);
            }
        }
    }

    private void shoot(Mob mob, LivingEntity target, MobBehavior.Ranged ranged) {
        if (mob.getLocation().distanceSquared(target.getLocation()) > ranged.range() * ranged.range()
                || !mob.hasLineOfSight(target) || !ready(mob.getUniqueId() + ">", ranged.interval())) {
            return;
        }
        var direction = target.getEyeLocation().toVector().subtract(mob.getEyeLocation().toVector()).normalize();
        Projectile projectile = mob.launchProjectile(ranged.projectile().getEntityClass().asSubclass(Projectile.class),
                direction.multiply(ranged.speed()));
        projectile.setShooter(mob);
        if (projectile instanceof AbstractArrow arrow) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            if (ranged.damage() > 0) {
                arrow.setDamage(ranged.damage());
            }
        }
        mob.swingMainHand();
    }

    /** Mob domado sem alvo: segue o dono; se estiver longe demais, teleporta. */
    private void follow(Mob mob) {
        UUID ownerId = ownerOf(mob);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.getWorld().equals(mob.getWorld())) {
            return;
        }
        double distance = mob.getLocation().distanceSquared(owner.getLocation());
        if (distance > 30 * 30) {
            mob.teleport(owner.getLocation());
        } else if (distance > 8 * 8) {
            mob.getPathfinder().moveTo(owner.getLocation(), 1.2);
        }
    }
}
