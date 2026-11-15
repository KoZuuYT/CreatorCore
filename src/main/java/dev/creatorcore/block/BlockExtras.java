package dev.creatorcore.block;

import java.util.List;
import java.util.Locale;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Opções extras de um bloco (todas opcionais): sons, partículas, experiência, Fortuna, comandos e resistência
 * a explosões.
 *
 * @param xp            experiência dropada ao quebrar (0 = nenhuma)
 * @param placeSound    som ao colocar (ex.: block.stone.place) ou {@code null}
 * @param breakSound    som ao quebrar ou {@code null}
 * @param particle      partícula ao quebrar ou {@code null}
 * @param particleCount quantidade de partículas
 * @param fortune       Fortuna multiplica os drops
 * @param explosionProof não é destruído por explosões
 * @param placeCommands comandos do console ao colocar ({@code %jogador% %x% %y% %z%})
 * @param breakCommands comandos do console ao quebrar
 */
public record BlockExtras(int xp, String placeSound, String breakSound, Particle particle, int particleCount,
                          boolean fortune, boolean explosionProof, List<String> placeCommands,
                          List<String> breakCommands) {

    public static final BlockExtras NONE = new BlockExtras(0, null, null, null, 0, false, false, List.of(), List.of());

    public static BlockExtras parse(ConfigurationSection s) {
        int xp = s.getInt("xp", 0);
        if (xp < 0 || xp > 10000) {
            throw new IllegalArgumentException("xp deve ser de 0 a 10000");
        }
        Particle particle = null;
        if (s.contains("particula")) {
            try {
                particle = Particle.valueOf(s.getString("particula", "").toUpperCase(Locale.ROOT));
                if (particle.getDataType() != Void.class) {
                    throw new IllegalArgumentException("partícula '" + particle + "' exige dados extras (use ex.: CLOUD, FLAME, HEART)");
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("partícula")) {
                    throw e;
                }
                throw new IllegalArgumentException("particula inválida '" + s.getString("particula") + "' (ex.: CLOUD, FLAME, HEART)");
            }
        }
        int count = s.getInt("particula-quantidade", 10);
        if (count < 1 || count > 200) {
            throw new IllegalArgumentException("particula-quantidade deve ser de 1 a 200");
        }
        return new BlockExtras(xp, sound(s, "som-colocar"), sound(s, "som-quebrar"), particle, count,
                s.getBoolean("fortuna", false), s.getBoolean("resiste-explosao", false),
                List.copyOf(s.getStringList("comandos-colocar")), List.copyOf(s.getStringList("comandos-quebrar")));
    }

    private static String sound(ConfigurationSection s, String key) {
        String value = s.getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        if (NamespacedKey.fromString(value.toLowerCase(Locale.ROOT)) == null) {
            throw new IllegalArgumentException(key + ": som inválido '" + value + "' (ex.: block.stone.break)");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
