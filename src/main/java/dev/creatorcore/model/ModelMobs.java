package dev.creatorcore.model;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.mob.MobDefinition;
import dev.creatorcore.registry.CreationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Mobs com {@code modelo:} ficam invisíveis e recebem item displays com o modelo importado, que acompanham
 * a posição e a rotação do mob a cada tick. Modelos com animações viram um display por osso, e as
 * animações (parado, andando, ataque, morte) tocam conforme o que o mob faz. Os displays não são salvos
 * no mundo: são recriados quando o mob carrega (chunk ou reinício).
 */
public final class ModelMobs implements Listener {

    /** Um display e o índice do osso que ele mostra (-1 = modelo inteiro, sem animação). */
    private record Part(ItemDisplay display, int bone) {
    }

    private final class Entry {
        final UUID mobId;
        final MobDefinition def;
        final ModelImporter.Imported model;
        final List<Part> parts = new ArrayList<>();
        Location last;
        String desired = "";
        String state = "";
        long stateStart;
        long attackUntil;
        long dyingUntil = -1;
        Location deathLocation;

        Entry(UUID mobId, MobDefinition def, ModelImporter.Imported model) {
            this.mobId = mobId;
            this.def = def;
            this.model = model;
        }
    }

    private final CreatorCorePlugin plugin;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private BukkitTask task;
    private long tick;

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
        entries.values().forEach(this::removeParts);
        entries.clear();
    }

    /** Recria os displays de todos os mobs carregados (após recarregar ou iniciar). */
    public void refreshAll() {
        entries.values().forEach(this::removeParts);
        entries.clear();
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

    /** O mob atacou: toca a animação de ataque (uma vez). */
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        Entry entry = entries.get(event.getDamager().getUniqueId());
        if (entry == null || entry.model.rig() == null) {
            return;
        }
        var animation = animation(entry, "ataque");
        if (animation != null) {
            entry.attackUntil = tick + (long) Math.ceil(animation.length() * 20);
        }
    }

    private void attachIfCustom(LivingEntity living) {
        String id = living.getPersistentDataContainer().get(plugin.mobKey(), PersistentDataType.STRING);
        if (id != null && plugin.registry().get(CreationType.MOB, id).orElse(null) instanceof MobDefinition def) {
            attach(living, def);
        }
    }

    public void attach(LivingEntity mob, MobDefinition def) {
        ModelImporter.Imported model = plugin.models().get(def.model());
        if (model == null) {
            return;
        }
        Entry existing = entries.get(mob.getUniqueId());
        if (existing != null && existing.parts.stream().allMatch(p -> p.display().isValid())) {
            return;
        }
        if (existing != null) {
            removeParts(existing);
        }
        mob.setInvisible(true);
        Entry entry = new Entry(mob.getUniqueId(), def, model);
        Rig rig = model.rig();
        if (rig == null) {
            entry.parts.add(new Part(spawnDisplay(mob.getLocation(), def.model()), -1));
            applyStatic(entry);
        } else {
            for (int i = 0; i < rig.bones().size(); i++) {
                if (rig.boneModels().containsKey(rig.bones().get(i).key())) {
                    entry.parts.add(new Part(spawnDisplay(mob.getLocation(), Rig.partId(def.model(), i)), i));
                }
            }
            entry.stateStart = tick;
            pose(entry, 0);
        }
        entry.last = mob.getLocation();
        entries.put(mob.getUniqueId(), entry);
    }

    private ItemDisplay spawnDisplay(Location where, String modelName) {
        ItemStack stack = new ItemStack(Material.STICK);
        var meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey(ModelManager.NAMESPACE, modelName));
        stack.setItemMeta(meta);
        return where.getWorld().spawn(where, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setPersistent(false);
        });
    }

    /** Modelo sem animação: escala e posição fixas. */
    private void applyStatic(Entry entry) {
        float scale = (float) entry.def.modelScale();
        entry.parts.get(0).display().setTransformation(new Transformation(
                new Vector3f(0, 0.5f * scale + (float) entry.def.modelOffsetY(), 0),
                new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
    }

    /** Morte: com animação de morte, o modelo a toca no lugar antes de sumir. */
    public void detach(UUID mobId, Location deathLocation) {
        Entry entry = entries.get(mobId);
        if (entry == null) {
            return;
        }
        var animation = entry.model.rig() == null ? null : animation(entry, "morte");
        if (animation == null) {
            removeParts(entry);
            entries.remove(mobId);
            return;
        }
        entry.dyingUntil = tick + (long) Math.ceil(animation.length() * 20) + 10;
        entry.deathLocation = deathLocation;
        entry.desired = "";
        entry.state = "";
        entry.stateStart = tick;
    }

    private void removeParts(Entry entry) {
        entry.parts.forEach(p -> p.display().remove());
    }

    // ── Animação ────────────────────────────────────────────────

    private Rig.Animation animation(Entry entry, String role) {
        Rig rig = entry.model.rig();
        if (rig == null) {
            return null;
        }
        String chosen = entry.def.animations().get(role);
        if (chosen != null) {
            return rig.animations().get(chosen);
        }
        String[] keywords = switch (role) {
            case "parado" -> new String[] {"idle", "parado", "stand", "wait"};
            case "andando" -> new String[] {"walk", "andar", "andando", "run", "move"};
            case "ataque" -> new String[] {"attack", "ataque", "atacar", "bite", "hit", "slash"};
            default -> new String[] {"death", "morte", "die"};
        };
        for (var animation : rig.animations().values()) {
            String name = animation.name().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (name.contains(keyword)) {
                    return animation;
                }
            }
        }
        return null;
    }

    private void pose(Entry entry, double seconds) {
        Rig rig = entry.model.rig();
        var animation = animation(entry, entry.state.isEmpty() ? "parado" : entry.state);
        Matrix4d[] poses = rig.pose(animation, seconds);
        float scale = (float) entry.def.modelScale();
        for (Part part : entry.parts) {
            // Ponto do modelo (unidades, 16 = 1 bloco) -> pose do osso -> bloco relativo ao mob.
            Matrix4f matrix = new Matrix4f()
                    .translate(0, 0.5f * scale + (float) entry.def.modelOffsetY(), 0)
                    .scale(scale / 16f)
                    .translate(-8, -8, -8)
                    .mul(new Matrix4f(poses[part.bone()]))
                    .translate(8, 8, 8)
                    .scale(16f);
            part.display().setTransformationMatrix(matrix);
        }
    }

    private void tick() {
        tick++;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            boolean dying = entry.dyingUntil >= 0;
            Entity mob = Bukkit.getEntity(entry.mobId);
            boolean gone = mob == null || !mob.isValid() || mob.isDead();
            if (dying ? tick >= entry.dyingUntil
                    : gone || entry.parts.stream().anyMatch(p -> !p.display().isValid())) {
                removeParts(entry);
                it.remove();
                continue;
            }
            Location where = dying ? entry.deathLocation.clone() : mob.getLocation();
            where.setYaw((float) (where.getYaw() + entry.def.modelYaw()));
            for (Part part : entry.parts) {
                part.display().teleport(where);
            }
            if (entry.model.rig() == null) {
                continue;
            }
            String state;
            if (dying) {
                state = "morte";
            } else if (entry.attackUntil > tick) {
                state = "ataque";
            } else {
                Location now = mob.getLocation();
                boolean moving = entry.last != null && entry.last.getWorld() == now.getWorld()
                        && Math.hypot(now.getX() - entry.last.getX(), now.getZ() - entry.last.getZ()) > 0.01;
                entry.last = now;
                state = moving ? "andando" : "parado";
            }
            boolean changed = !state.equals(entry.desired);
            if (changed) {
                entry.desired = state;
                entry.stateStart = tick;
                // Sem animação para o estado (ex.: nada de "andando"), cai em "parado".
                entry.state = animation(entry, state) != null ? state : "parado";
            }
            // Atualiza a pose a cada 2 ticks (o cliente interpola entre as atualizações).
            if (changed || tick % 2 == 0) {
                pose(entry, (tick - entry.stateStart) / 20.0);
            }
        }
    }
}
