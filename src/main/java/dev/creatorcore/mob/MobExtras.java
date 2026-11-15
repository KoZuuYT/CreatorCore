package dev.creatorcore.mob;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffectType;

/**
 * Opções extras de um mob (todas opcionais): filhote, IA, gravidade, invulnerabilidade, imunidades, efeitos
 * permanentes, sons, barra de chefe e comandos ao morrer.
 *
 * @param effects efeito -> nível (1 = I), aplicados sem expirar
 */
public record MobExtras(boolean baby, boolean noAi, boolean noGravity, boolean invulnerable, Boolean pickup,
                        boolean nameVisible, Set<DamageCause> immune, Map<PotionEffectType, Integer> effects,
                        String hurtSound, String deathSound, Boss boss, List<String> deathCommands) {

    public static final MobExtras NONE = new MobExtras(false, false, false, false, null, true, Set.of(), Map.of(),
            null, null, null, List.of());

    /** Barra de vida estilo chefe, mostrada a quem está por perto. */
    public record Boss(BarColor color, BarStyle style, double radius) {
    }

    private static final Map<String, List<DamageCause>> IMMUNITY = Map.ofEntries(
            Map.entry("fogo", List.of(DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR)),
            Map.entry("explosao", List.of(DamageCause.ENTITY_EXPLOSION, DamageCause.BLOCK_EXPLOSION)),
            Map.entry("projetil", List.of(DamageCause.PROJECTILE)),
            Map.entry("queda", List.of(DamageCause.FALL)),
            Map.entry("afogamento", List.of(DamageCause.DROWNING)),
            Map.entry("magia", List.of(DamageCause.MAGIC, DamageCause.POISON, DamageCause.WITHER)),
            Map.entry("corpo-a-corpo", List.of(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK)),
            Map.entry("raio", List.of(DamageCause.LIGHTNING)),
            Map.entry("sufocamento", List.of(DamageCause.SUFFOCATION)),
            Map.entry("congelamento", List.of(DamageCause.FREEZE)),
            Map.entry("cacto", List.of(DamageCause.CONTACT)));

    /** Nomes aceitos em {@code imune-a}. */
    public static Set<String> immunityNames() {
        return new java.util.TreeSet<>(IMMUNITY.keySet());
    }

    public static MobExtras parse(ConfigurationSection s) {
        Set<DamageCause> immune = new LinkedHashSet<>();
        for (String name : s.getStringList("imune-a")) {
            List<DamageCause> causes = IMMUNITY.get(name.toLowerCase(Locale.ROOT));
            if (causes == null) {
                throw new IllegalArgumentException("imune-a: '" + name + "' inválido (" + String.join(", ", immunityNames()) + ")");
            }
            immune.addAll(causes);
        }
        Map<PotionEffectType, Integer> effects = new LinkedHashMap<>();
        ConfigurationSection e = s.getConfigurationSection("efeitos");
        if (e != null) {
            for (String name : e.getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
                int level = e.getInt(name, 1);
                if (type == null || level < 1 || level > 255) {
                    throw new IllegalArgumentException("efeitos: '" + name + "' inválido (efeito vanilla, nível 1 a 255)");
                }
                effects.put(type, level);
            }
        }
        Boss boss = null;
        ConfigurationSection b = s.getConfigurationSection("barra-chefe");
        if (b != null) {
            BarColor color;
            BarStyle style;
            try {
                color = BarColor.valueOf(b.getString("cor", "RED").toUpperCase(Locale.ROOT));
                style = BarStyle.valueOf(b.getString("estilo", "SOLID").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("barra-chefe: cor (PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE) ou "
                        + "estilo (SOLID, SEGMENTED_6, SEGMENTED_10, SEGMENTED_12, SEGMENTED_20) inválidos");
            }
            double radius = b.getDouble("raio", 40);
            if (radius < 4 || radius > 128) {
                throw new IllegalArgumentException("barra-chefe: raio deve ser de 4 a 128 blocos");
            }
            boss = new Boss(color, style, radius);
        }
        Boolean pickup = s.contains("pegar-itens") ? s.getBoolean("pegar-itens") : null;
        return new MobExtras(s.getBoolean("filhote", false), s.getBoolean("sem-ia", false), s.getBoolean("sem-gravidade", false),
                s.getBoolean("invulneravel", false), pickup, s.getBoolean("nome-visivel", true), Set.copyOf(immune),
                Map.copyOf(effects), sound(s, "som-ferido"), sound(s, "som-morte"), boss,
                List.copyOf(s.getStringList("comandos-morte")));
    }

    private static String sound(ConfigurationSection s, String key) {
        String value = s.getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (NamespacedKey.fromString(value.toLowerCase(Locale.ROOT)) == null) {
            throw new IllegalArgumentException(key + ": som inválido '" + value + "' (ex.: entity.zombie.hurt)");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
