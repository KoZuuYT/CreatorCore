package dev.creatorcore.item;

import java.util.List;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * Habilidade de um item: ao acontecer o {@code trigger}, executa as {@code actions}.
 *
 * @param trigger  right_click, left_click, sneak_right_click ou hit (bater numa entidade)
 * @param cooldown recarga em segundos (0 = sem)
 * @param consume  gasta uma unidade do item ao usar
 */
public record AbilitySpec(String trigger, double cooldown, boolean consume, List<Action> actions) {

    /**
     * Uma ação. Campos usados por tipo: efeito (potion, ticks, amplifier), curar/dano/impulso (value),
     * comando/som (text, value = volume), projetil (entity, value = velocidade), raio (nenhum).
     *
     * @param onTarget a ação vale para o alvo (entidade atingida ou mirada) em vez do próprio jogador
     */
    public record Action(String type, PotionEffectType potion, int ticks, int amplifier, double value, String text,
                         EntityType entity, boolean onTarget) {
    }
}
