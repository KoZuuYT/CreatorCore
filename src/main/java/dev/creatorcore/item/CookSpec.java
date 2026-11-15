package dev.creatorcore.item;

/**
 * Receita de cozimento que produz o item: {@code kind} é furnace, blasting, smoking ou campfire;
 * {@code input} é um material ou {@code cc:<id>}; {@code time} em ticks.
 */
public record CookSpec(String kind, String input, float xp, int time, int amount) {
}
