package dev.creatorcore.block;

import java.util.Locale;
import org.bukkit.Material;

/** Tipo, nível e velocidade de uma ferramenta vanilla, deduzidos do nome do material. */
public record ToolInfo(Type type, int tier, double speed) {

    public enum Type {
        NONE, PICKAXE, AXE, SHOVEL, HOE;

        /** @return o tipo, ou {@code null} se o nome for inválido. */
        public static Type parse(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "none", "nenhuma" -> NONE;
                case "pickaxe", "picareta" -> PICKAXE;
                case "axe", "machado" -> AXE;
                case "shovel", "pa" -> SHOVEL;
                case "hoe", "enxada" -> HOE;
                default -> null;
            };
        }
    }

    public static final ToolInfo HAND = new ToolInfo(Type.NONE, -1, 1);

    /** Nível mínimo pelo nome (madeira/ouro = 0 … netherite = 4); {@code -1} se inválido. */
    public static int parseTier(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "wood", "wooden", "madeira", "gold", "golden", "ouro" -> 0;
            case "stone", "pedra" -> 1;
            case "iron", "ferro" -> 2;
            case "diamond", "diamante" -> 3;
            case "netherite" -> 4;
            default -> -1;
        };
    }

    public static ToolInfo of(Material material) {
        String name = material.name();
        Type type;
        if (name.endsWith("_PICKAXE")) {
            type = Type.PICKAXE;
        } else if (name.endsWith("_AXE")) {
            type = Type.AXE;
        } else if (name.endsWith("_SHOVEL")) {
            type = Type.SHOVEL;
        } else if (name.endsWith("_HOE")) {
            type = Type.HOE;
        } else {
            return HAND;
        }
        if (name.startsWith("WOODEN_")) {
            return new ToolInfo(type, 0, 2);
        } else if (name.startsWith("STONE_")) {
            return new ToolInfo(type, 1, 4);
        } else if (name.startsWith("IRON_")) {
            return new ToolInfo(type, 2, 6);
        } else if (name.startsWith("DIAMOND_")) {
            return new ToolInfo(type, 3, 8);
        } else if (name.startsWith("NETHERITE_")) {
            return new ToolInfo(type, 4, 9);
        } else if (name.startsWith("GOLDEN_")) {
            return new ToolInfo(type, 0, 12);
        }
        return HAND;
    }
}
