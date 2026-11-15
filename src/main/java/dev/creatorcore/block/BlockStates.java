package dev.creatorcore.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.creatorcore.CreatorCorePlugin;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

/**
 * Tabela dos estados do note block usados como blocos customizados.
 * O estado {@code i} (1 a 799) é: instrumento {@code i / 50}, nota {@code (i % 50) / 2}, powered {@code i % 2 == 1}.
 * Tem de bater com tools/pack/build.py. Os modelos vêm de {@code blocks.json} (dentro do jar).
 */
public final class BlockStates {

    private static final String[] INSTRUMENTS = {
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"};

    private final Map<String, Integer> models = new HashMap<>();

    public BlockStates(CreatorCorePlugin plugin) {
        try (var in = plugin.getResource("blocks.json")) {
            if (in == null) {
                throw new IOException("blocks.json ausente no jar");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("modelos");
                for (var entry : root.entrySet()) {
                    int state = entry.getValue().getAsInt();
                    if (state < 1 || state >= INSTRUMENTS.length * 50) {
                        plugin.getLogger().warning("blocks.json: estado inválido para '" + entry.getKey() + "'.");
                        continue;
                    }
                    models.put(entry.getKey(), state);
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("Não foi possível ler blocks.json: " + e);
        }
    }

    /** @return o índice do estado do modelo, ou {@code null} se o modelo não existe. */
    public Integer state(String model) {
        return models.get(model);
    }

    public BlockData data(int state) {
        return Bukkit.createBlockData("minecraft:note_block[instrument=" + INSTRUMENTS[state / 50]
                + ",note=" + ((state % 50) / 2) + ",powered=" + (state % 2 == 1) + "]");
    }
}
