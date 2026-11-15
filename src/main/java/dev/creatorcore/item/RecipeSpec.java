package dev.creatorcore.item;

import java.util.List;
import java.util.Map;

/**
 * Receita de um item, como escrita no YAML (ainda sem resolver ingredientes).
 * Ingredientes são nomes de material ({@code EMERALD}) ou {@code cc:<id>} para outro item do CreatorCore.
 *
 * @param shaped      {@code true} = com forma ({@code shape} + {@code ingredients}); {@code false} = sem forma ({@code loose})
 * @param shape       1 a 3 linhas de até 3 caracteres; espaço = vazio
 * @param ingredients caractere da forma → ingrediente
 * @param loose       lista de ingredientes (sem forma)
 * @param amount      quantidade produzida
 */
public record RecipeSpec(boolean shaped, List<String> shape, Map<Character, String> ingredients,
                         List<String> loose, int amount) {
}
