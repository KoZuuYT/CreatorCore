package dev.creatorcore.item;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.Creation;
import org.bukkit.inventory.ItemStack;

/** Criação que existe como item no inventário (itens e blocos), podendo ter receita e servir de ingrediente. */
public interface ItemSource extends Creation {

    ItemStack create(CreatorCorePlugin plugin, int amount);

    /** Receita de criação, ou {@code null}. */
    RecipeSpec recipe();

    /** Receitas de fornalha, alto-forno, defumador e fogueira que produzem este item. */
    java.util.List<CookSpec> cooking();
}
