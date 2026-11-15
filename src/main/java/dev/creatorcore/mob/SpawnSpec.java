package dev.creatorcore.mob;

import java.util.Set;

/**
 * Spawn natural de um mob customizado: quando o jogo faria nascer o mob vanilla de base, com a
 * {@code chance} dada o nascimento é trocado pelo customizado (se o mundo e o bioma servirem e o
 * {@code limit} de mobs deste tipo no mundo não tiver sido atingido).
 *
 * @param worlds nomes de mundos (vazio = qualquer)
 * @param biomes biomas, só o nome (ex.: {@code plains}); vazio = qualquer
 * @param chance de 0 a 1
 * @param limit  máximo deste mob vivo no mundo
 */
public record SpawnSpec(Set<String> worlds, Set<String> biomes, double chance, int limit) {
}
