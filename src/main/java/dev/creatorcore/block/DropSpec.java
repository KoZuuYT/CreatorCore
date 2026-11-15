package dev.creatorcore.block;

/**
 * Um drop do bloco: material vanilla ({@code DIAMOND}) ou {@code cc:<id>}, em quantidade de {@code min} a {@code max}.
 */
public record DropSpec(String item, int min, int max) {
}
