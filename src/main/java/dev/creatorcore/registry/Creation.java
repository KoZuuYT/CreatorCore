package dev.creatorcore.registry;

/** Uma criação customizada (item, bloco, mob…), identificada por um id único dentro do seu tipo. */
public interface Creation {

    /** Id em minúsculas: letras, números e {@code _} (ex.: {@code espada_teste}). */
    String id();

    CreationType type();
}
