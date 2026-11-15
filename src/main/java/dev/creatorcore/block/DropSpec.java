package dev.creatorcore.block;

/**
 * Um drop: material vanilla ({@code DIAMOND}) ou {@code cc:<id>}, em quantidade de {@code min} a {@code max}.
 *
 * @param chance     probabilidade de 0 a 1 (1 = sempre)
 * @param onlyPlayer em mobs: só dropa se o jogador deu o golpe final
 */
public record DropSpec(String item, int min, int max, double chance, boolean onlyPlayer) {

    public DropSpec(String item, int min, int max) {
        this(item, min, max, 1.0, false);
    }
}
