package dev.creatorcore.item;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;

/** Executa as ações de {@link AbilitySpec} para qualquer ser vivo (jogador usando um item ou mob usando uma habilidade). */
public final class AbilityActions {

    private AbilityActions() {
    }

    /**
     * @param actor  quem executa (jogador ou mob)
     * @param target entidade atingida/mirada (pode ser null)
     */
    public static void apply(LivingEntity actor, Entity target, AbilitySpec.Action action) {
        Entity subject = action.onTarget() ? target : actor;
        LivingEntity living = subject instanceof LivingEntity l ? l : null;
        switch (action.type()) {
            case "efeito" -> {
                if (living != null) {
                    living.addPotionEffect(new org.bukkit.potion.PotionEffect(action.potion(), action.ticks(),
                            action.amplifier()));
                }
            }
            case "curar" -> {
                if (living != null) {
                    var max = living.getAttribute(Attribute.MAX_HEALTH);
                    living.setHealth(Math.min(max == null ? 20 : max.getValue(), living.getHealth() + action.value()));
                }
            }
            case "dano" -> {
                if (living != null) {
                    living.damage(action.value(), actor);
                }
            }
            case "comando" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    action.text().replace("%jogador%", actor.getName()));
            case "projetil" -> {
                var launched = actor.launchProjectile(action.entity().getEntityClass().asSubclass(Projectile.class),
                        actor.getEyeLocation().getDirection().multiply(action.value()));
                launched.setShooter(actor);
            }
            case "som" -> actor.getWorld().playSound(actor.getLocation(), action.text().toLowerCase(Locale.ROOT),
                    (float) action.value(), 1f);
            case "raio" -> {
                if (subject != null) {
                    subject.getWorld().strikeLightning(subject.getLocation());
                }
            }
            case "impulso" -> actor.setVelocity(actor.getLocation().getDirection().multiply(action.value()));
            default -> { }
        }
    }
}
