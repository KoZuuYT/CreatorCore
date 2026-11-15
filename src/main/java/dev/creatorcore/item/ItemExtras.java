package dev.creatorcore.item;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Opções extras de personalização de um item (todas opcionais): pilha máxima, raridade, durabilidade,
 * tooltip, comida, resistência a fogo, encantabilidade e uso como capacete.
 */
public record ItemExtras(Integer maxStack, ItemRarity rarity, Integer maxDamage, boolean hideTooltip,
                         Set<ItemFlag> hide, Food food, boolean fireResistant, Integer enchantable, boolean hat) {

    public static final ItemExtras NONE = new ItemExtras(null, null, null, false, Set.of(), null, false, null, false);

    /** Item comestível. */
    public record Food(int nutrition, double saturation, boolean alwaysEat, double eatSeconds) {
    }

    private static final java.util.Map<String, ItemFlag> HIDE = java.util.Map.of(
            "atributos", ItemFlag.HIDE_ATTRIBUTES,
            "encantamentos", ItemFlag.HIDE_ENCHANTS,
            "inquebravel", ItemFlag.HIDE_UNBREAKABLE,
            "extra", ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

    /** Lê as opções do YAML do item; lança {@link IllegalArgumentException} para valores inválidos. */
    static ItemExtras parse(ConfigurationSection s, Material material) {
        Integer maxStack = null;
        if (s.contains("max-pilha")) {
            int value = s.getInt("max-pilha");
            if (value < 1 || value > 99) {
                throw new IllegalArgumentException("max-pilha deve ser de 1 a 99");
            }
            maxStack = value;
        }
        ItemRarity rarity = null;
        if (s.contains("raridade")) {
            try {
                rarity = ItemRarity.valueOf(s.getString("raridade", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("raridade inválida (common, uncommon, rare ou epic)");
            }
        }
        Integer maxDamage = null;
        if (s.contains("durabilidade")) {
            int value = s.getInt("durabilidade");
            if (value < 1 || value > 100000) {
                throw new IllegalArgumentException("durabilidade deve ser de 1 a 100000");
            }
            maxDamage = value;
        }
        if (maxStack != null && maxStack > 1 && (maxDamage != null || material.getMaxDurability() > 0)) {
            throw new IllegalArgumentException("item com durabilidade não pode empilhar (max-pilha deve ser 1)");
        }
        Set<ItemFlag> hide = new LinkedHashSet<>();
        for (String name : s.getStringList("esconder")) {
            ItemFlag flag = HIDE.get(name.toLowerCase(Locale.ROOT));
            if (flag == null) {
                throw new IllegalArgumentException("esconder: '" + name + "' inválido (atributos, encantamentos, inquebravel, extra)");
            }
            hide.add(flag);
        }
        Food food = null;
        ConfigurationSection f = s.getConfigurationSection("comida");
        if (f != null) {
            int nutrition = f.getInt("nutricao", 4);
            double saturation = f.getDouble("saturacao", 2);
            double seconds = f.getDouble("tempo", 1.6);
            if (nutrition < 0 || nutrition > 20 || saturation < 0 || saturation > 20 || seconds < 0.05 || seconds > 60) {
                throw new IllegalArgumentException("comida: nutricao (0-20), saturacao (0-20) ou tempo (0.05-60 s) inválidos");
            }
            food = new Food(nutrition, saturation, f.getBoolean("sempre-comer", false), seconds);
        }
        Integer enchantable = null;
        if (s.contains("encantabilidade")) {
            int value = s.getInt("encantabilidade");
            if (value < 1 || value > 100) {
                throw new IllegalArgumentException("encantabilidade deve ser de 1 a 100");
            }
            enchantable = value;
        }
        return new ItemExtras(maxStack, rarity, maxDamage, s.getBoolean("esconder-tooltip", false), Set.copyOf(hide),
                food, s.getBoolean("resistente-fogo", false), enchantable, s.getBoolean("cabeca", false));
    }

    /** Aplica ao meta do item. */
    void apply(ItemMeta meta) {
        if (maxStack != null) {
            meta.setMaxStackSize(maxStack);
        }
        if (rarity != null) {
            meta.setRarity(rarity);
        }
        if (maxDamage != null && meta instanceof Damageable damageable) {
            damageable.setMaxDamage(maxDamage);
        }
        if (hideTooltip) {
            meta.setHideTooltip(true);
        }
        hide.forEach(meta::addItemFlags);
        if (enchantable != null) {
            meta.setEnchantable(enchantable);
        }
        if (fireResistant) {
            meta.setDamageResistant(org.bukkit.tag.DamageTypeTags.IS_FIRE);
        }
        if (food != null) {
            var component = meta.getFood();
            component.setNutrition(food.nutrition());
            component.setSaturation((float) food.saturation());
            component.setCanAlwaysEat(food.alwaysEat());
            meta.setFood(component);
        }
        if (hat) {
            var equippable = meta.getEquippable();
            equippable.setSlot(EquipmentSlot.HEAD);
            meta.setEquippable(equippable);
        }
    }

    /** Componentes que só existem pela API de data components (comer o item). */
    void applyComponents(ItemStack stack) {
        if (food != null) {
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE,
                    io.papermc.paper.datacomponent.item.Consumable.consumable()
                            .consumeSeconds((float) food.eatSeconds()).build());
        }
    }
}
