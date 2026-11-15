package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationType;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Definição de um item customizado, lida de {@code plugins/CreatorCore/items/*.yml}.
 * O item criado carrega o id em {@code creatorcore:id} para ser reconhecido depois.
 *
 * @param attributes modificadores de atributo; se houver algum, SUBSTITUEM os do item base (ex.: o dano da espada)
 * @param model      nome do modelo no resource pack (gravado em custom_model_data), ou {@code null}
 * @param recipe     receita de criação, ou {@code null}
 */
public record ItemDefinition(String id, Material material, String name, List<String> lore,
                             List<AttributeSpec> attributes, Map<Enchantment, Integer> enchantments,
                             boolean unbreakable, boolean glint, String model, RecipeSpec recipe,
                             java.util.List<CookSpec> cooking, java.util.List<AbilitySpec> abilities,
                             ArmorSpec armor, ItemExtras extras) implements ItemSource {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Override
    public CreationType type() {
        return CreationType.ITEM;
    }

    @Override
    public ItemStack create(CreatorCorePlugin plugin, int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (name != null && !name.isBlank()) {
            meta.displayName(noItalic(MM.deserialize(name)));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(line -> noItalic(MM.deserialize(line))).toList());
        }
        int index = 0;
        for (AttributeSpec spec : attributes) {
            // Chave única por item e posição: o jogo exige ids distintos para os modificadores.
            NamespacedKey key = new NamespacedKey(plugin, id + "_" + index++);
            meta.addAttributeModifier(spec.attribute(),
                    new AttributeModifier(key, spec.amount(), spec.operation(), spec.slot()));
        }
        if (armor != null) {
            var equippable = meta.getEquippable();
            equippable.setSlot(armor.slot());
            equippable.setModel(new NamespacedKey(dev.creatorcore.model.ModelManager.NAMESPACE, armor.texture()));
            meta.setEquippable(equippable);
            var group = switch (armor.slot()) {
                case HEAD -> org.bukkit.inventory.EquipmentSlotGroup.HEAD;
                case CHEST -> org.bukkit.inventory.EquipmentSlotGroup.CHEST;
                case LEGS -> org.bukkit.inventory.EquipmentSlotGroup.LEGS;
                default -> org.bukkit.inventory.EquipmentSlotGroup.FEET;
            };
            if (armor.defense() != 0) {
                meta.addAttributeModifier(org.bukkit.attribute.Attribute.ARMOR, new AttributeModifier(
                        new NamespacedKey(plugin, id + "_armor"), armor.defense(), AttributeModifier.Operation.ADD_NUMBER, group));
            }
            if (armor.toughness() != 0) {
                meta.addAttributeModifier(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                        new NamespacedKey(plugin, id + "_toughness"), armor.toughness(), AttributeModifier.Operation.ADD_NUMBER, group));
            }
        }
        enchantments.forEach((enchantment, level) -> meta.addEnchant(enchantment, level, true));
        if (unbreakable) {
            meta.setUnbreakable(true);
        }
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (model != null) {
            // O pack escolhe o modelo por esta string; sem o pack, o item continua com o visual vanilla.
            CustomModelDataComponent data = meta.getCustomModelDataComponent();
            data.setStrings(List.of(model));
            meta.setCustomModelDataComponent(data);
            if (plugin.models().has(model)) {
                // Modelo importado do Blockbench (models/<id>.bbmodel): o pack próprio o define como creatorcore:<id>.
                meta.setItemModel(new org.bukkit.NamespacedKey(dev.creatorcore.model.ModelManager.NAMESPACE, model));
            }
        }
        meta.getPersistentDataContainer().set(plugin.idKey(), PersistentDataType.STRING, id);
        extras.apply(meta);
        stack.setItemMeta(meta);
        extras.applyComponents(stack);
        return stack;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
