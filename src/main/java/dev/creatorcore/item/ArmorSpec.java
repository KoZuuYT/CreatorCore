package dev.creatorcore.item;

import org.bukkit.inventory.EquipmentSlot;

/**
 * Armadura customizada: o item vira vestível no {@code slot} e usa a textura {@code texture}
 * (arquivos {@code armor/<texture>_1.png} e {@code _2.png}) no corpo do jogador.
 *
 * @param defense   pontos de armadura (0 = nenhum)
 * @param toughness resistência da armadura (0 = nenhuma)
 */
public record ArmorSpec(EquipmentSlot slot, String texture, double defense, double toughness) {
}
