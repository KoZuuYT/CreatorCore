package dev.creatorcore.mob;

import dev.creatorcore.item.AbilitySpec;
import java.util.List;
import org.bukkit.entity.EntityType;

/**
 * Comportamento de um mob ({@code comportamento:}).
 *
 * @param passive   nunca escolhe alvo (não ataca ninguém)
 * @param tame      domesticação por clique direito com um item, ou null
 * @param ranged    ataque à distância, ou null
 * @param abilities habilidades do mob (gatilhos atacar, ferido, periodica)
 */
public record MobBehavior(boolean passive, Tame tame, Ranged ranged, List<AbilitySpec> abilities) {

    /** @param item material ou cc:&lt;id&gt; usado para domar; @param chance 0-1 por tentativa */
    public record Tame(String item, double chance) {
    }

    /**
     * @param projectile tipo do projétil; @param speed velocidade; @param range alcance em blocos;
     * @param interval segundos entre disparos; @param damage dano da flecha (0 = o padrão)
     */
    public record Ranged(EntityType projectile, double speed, double range, double interval, double damage) {
    }
}
