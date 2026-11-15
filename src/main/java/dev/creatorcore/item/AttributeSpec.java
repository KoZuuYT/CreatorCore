package dev.creatorcore.item;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;

/** Um modificador de atributo aplicado ao item (ex.: dano de ataque na mão principal). */
public record AttributeSpec(Attribute attribute, double amount, AttributeModifier.Operation operation,
                            EquipmentSlotGroup slot) {
}
