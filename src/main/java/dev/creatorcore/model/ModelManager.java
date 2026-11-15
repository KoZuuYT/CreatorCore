package dev.creatorcore.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Importa os projetos do Blockbench de {@code plugins/CreatorCore/models/<id>.bbmodel} e gera os arquivos
 * do resource pack (modelo, texturas e definição de item em {@code creatorcore:<id>}).
 */
public final class ModelManager {

    public static final String NAMESPACE = "creatorcore";
    private static final long MAX_PROJECT_BYTES = 8L * 1024 * 1024;

    private final CreatorCorePlugin plugin;
    private volatile Map<String, ModelImporter.Imported> models = Map.of();

    public ModelManager(CreatorCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean has(String id) {
        return id != null && models.containsKey(id);
    }

    public int count() {
        return models.size();
    }

    /** Relê a pasta {@code models/}. @return quantos modelos foram importados */
    public int load() {
        File dir = new File(plugin.getDataFolder(), "models");
        dir.mkdirs();
        Map<String, ModelImporter.Imported> loaded = new LinkedHashMap<>();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".bbmodel")
                || name.toLowerCase(Locale.ROOT).endsWith(".geo.json"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                boolean geo = file.getName().toLowerCase(Locale.ROOT).endsWith(".geo.json");
                String id = file.getName().substring(0,
                        file.getName().length() - (geo ? ".geo.json" : ".bbmodel").length()).toLowerCase(Locale.ROOT);
                try {
                    if (loaded.containsKey(id)) {
                        throw new IllegalArgumentException("já existe outro modelo com o id '" + id + "'");
                    }
                    if (!CreationRegistry.isValidId(id)) {
                        throw new IllegalArgumentException("nome de arquivo inválido (use minúsculas, números e _)");
                    }
                    if (file.length() > MAX_PROJECT_BYTES) {
                        throw new IllegalArgumentException("arquivo grande demais (máx. 8 MB)");
                    }
                    JsonObject project = JsonParser.parseString(
                            Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (geo) {
                        File texture = new File(dir, file.getName().substring(0, file.getName().length() - ".geo.json".length()) + ".png");
                        if (!texture.isFile()) {
                            throw new IllegalArgumentException("falta a textura " + texture.getName() + " ao lado do arquivo");
                        }
                        loaded.put(id, ModelImporter.convertGeo(id, project, Files.readAllBytes(texture.toPath()),
                                NAMESPACE, plugin.getLogger()));
                    } else {
                        loaded.put(id, ModelImporter.convert(id, project, NAMESPACE, plugin.getLogger()));
                    }
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().warning("models/" + file.getName() + " ignorado (" + e.getMessage() + ").");
                }
            }
        }
        models = loaded;
        return loaded.size();
    }

    /** Arquivos para somar ao resource pack: caminho no zip -> conteúdo. */
    public Map<String, byte[]> packEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (var model : models.values()) {
            String id = model.id();
            entries.put("assets/" + NAMESPACE + "/models/item/" + id + ".json",
                    model.modelJson().getBytes(StandardCharsets.UTF_8));
            entries.put("assets/" + NAMESPACE + "/items/" + id + ".json",
                    ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NAMESPACE + ":item/" + id + "\"}}")
                            .getBytes(StandardCharsets.UTF_8));
            model.textures().forEach((name, png) ->
                    entries.put("assets/" + NAMESPACE + "/textures/item/" + name + ".png", png));
        }
        return entries;
    }
}
