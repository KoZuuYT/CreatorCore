package dev.creatorcore.profile;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationRegistry;
import dev.creatorcore.registry.CreationType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Perfis: coleções nomeadas de itens, blocos e mobs em {@code plugins/CreatorCore/perfis/<nome>/}
 * ({@code items/}, {@code blocks/}, {@code mobs/}, um arquivo por criação). Um perfil pode ser exportado como
 * um código de texto ({@code CC1:...}) e importado em outro servidor.
 */
public final class ProfileManager {

    public static final String CODE_PREFIX = "CC1:";
    private static final long MAX_CODE_BYTES = 4L * 1024 * 1024;
    private static final Pattern FILE_PATH = Pattern.compile("(items|blocks|mobs)/[a-z0-9_]+\\.yml");

    /** Código gerado e os arquivos (modelos/texturas) que o outro servidor precisa ter. */
    public record Exported(String code, List<String> needed) {
    }

    /** Resultado de uma importação. */
    public record Imported(String name, int items, int blocks, int mobs, List<String> missingFiles, List<String> duplicates) {
    }

    private final CreatorCorePlugin plugin;
    private final File root;
    private final Map<String, String> owners = new HashMap<>();
    private String active = "padrao";

    public ProfileManager(CreatorCorePlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "perfis");
        root.mkdirs();
        File state = new File(root, "_estado.yml");
        if (state.isFile()) {
            active = YamlConfiguration.loadConfiguration(state).getString("ativo", "padrao");
        }
        if (!exists(active)) {
            active = "padrao";
            create("padrao");
        }
    }

    // ── Perfis ──

    public File dir(String profile, CreationType type) {
        return new File(new File(root, profile), folder(type));
    }

    public static String folder(CreationType type) {
        return switch (type) {
            case ITEM -> "items";
            case BLOCK -> "blocks";
            case MOB -> "mobs";
        };
    }

    /** Nome de perfil válido: como um id, mas sem começar com "_" (reservado às pastas internas). */
    public static boolean validName(String name) {
        return CreationRegistry.isValidId(name) && !name.startsWith("_");
    }

    public boolean exists(String name) {
        return validName(name) && new File(root, name).isDirectory();
    }

    public List<String> names() {
        File[] dirs = root.listFiles(f -> f.isDirectory() && validName(f.getName()));
        if (dirs == null) {
            return List.of();
        }
        return Arrays.stream(dirs).map(File::getName).sorted().toList();
    }

    /** Cria o perfil (pastas vazias). @return false se o nome for inválido ou já existir */
    public boolean create(String name) {
        if (!validName(name) || exists(name)) {
            return false;
        }
        for (CreationType type : CreationType.values()) {
            dir(name, type).mkdirs();
        }
        return true;
    }

    public String active() {
        return active;
    }

    public boolean setActive(String name) {
        if (!exists(name)) {
            return false;
        }
        active = name;
        YamlConfiguration state = new YamlConfiguration();
        state.set("ativo", name);
        try {
            state.save(new File(root, "_estado.yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("Não foi possível salvar o perfil ativo: " + e.getMessage());
        }
        return true;
    }

    /** Move o perfil para perfis/_lixeira (não apaga de vez). */
    public boolean delete(String name) {
        if (!exists(name) || names().size() <= 1) {
            return false;
        }
        File trash = new File(root, "_lixeira");
        trash.mkdirs();
        File target = new File(trash, name + "-" + System.currentTimeMillis());
        if (!new File(root, name).renameTo(target)) {
            return false;
        }
        if (active.equals(name)) {
            setActive(names().get(0));
        }
        return true;
    }

    // ── Donos (qual perfil define cada criação), preenchido pelo carregador ──

    public void resetOwners() {
        owners.clear();
    }

    public void noteOwner(CreationType type, String id, String profile) {
        owners.put(type + ":" + id, profile);
    }

    /** Perfil que define a criação, ou {@code null} se ela vem das pastas soltas. */
    public String ownerOf(CreationType type, String id) {
        return owners.get(type + ":" + id);
    }

    /** Ids das criações do perfil, lidos dos arquivos. */
    public List<String> ids(String profile, CreationType type) {
        File[] files = dir(profile, type).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        Set<String> ids = new TreeSet<>();
        if (files != null) {
            for (File file : files) {
                ids.addAll(YamlConfiguration.loadConfiguration(file).getKeys(false));
            }
        }
        return new ArrayList<>(ids);
    }

    // ── Arquivos de criações ──

    /** Arquivo em que o editor grava a criação. */
    public File fileOf(String profile, CreationType type, String id) {
        return new File(dir(profile, type), id + ".yml");
    }

    /** Seção salva da criação (ou {@code null}). Procura no arquivo próprio e nos outros arquivos do perfil. */
    public ConfigurationSection read(String profile, CreationType type, String id) {
        File own = fileOf(profile, type, id);
        if (own.isFile()) {
            return YamlConfiguration.loadConfiguration(own).getConfigurationSection(id);
        }
        File[] files = dir(profile, type).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                ConfigurationSection s = YamlConfiguration.loadConfiguration(file).getConfigurationSection(id);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Converte uma seção em mapas aninhados simples (para gravar com ordem e sem referências). */
    public static Map<String, Object> plain(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? plain(child) : value);
        }
        return map;
    }

    public void save(String profile, CreationType type, String id, Map<String, Object> draft) throws IOException {
        // Se a criação vivia num arquivo agrupado, remove de lá para não duplicar.
        File own = fileOf(profile, type, id);
        File[] files = dir(profile, type).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                if (!file.equals(own)) {
                    YamlConfiguration other = YamlConfiguration.loadConfiguration(file);
                    if (other.contains(id)) {
                        other.set(id, null);
                        if (other.getKeys(false).isEmpty()) {
                            Files.delete(file.toPath());
                        } else {
                            other.save(file);
                        }
                    }
                }
            }
        }
        YamlConfiguration out = new YamlConfiguration();
        out.createSection(id, draft);
        dir(profile, type).mkdirs();
        out.save(own);
    }

    /** Remove a criação do perfil (vai para perfis/_lixeira). */
    public boolean remove(String profile, CreationType type, String id) throws IOException {
        ConfigurationSection s = read(profile, type, id);
        if (s == null) {
            return false;
        }
        File trash = new File(root, "_lixeira");
        trash.mkdirs();
        YamlConfiguration backup = new YamlConfiguration();
        backup.createSection(id, plain(s));
        backup.save(new File(trash, profile + "-" + folder(type) + "-" + id + "-" + System.currentTimeMillis() + ".yml"));
        File own = fileOf(profile, type, id);
        if (own.isFile()) {
            Files.delete(own.toPath());
        } else {
            File[] files = dir(profile, type).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    YamlConfiguration other = YamlConfiguration.loadConfiguration(file);
                    if (other.contains(id)) {
                        other.set(id, null);
                        other.save(file);
                    }
                }
            }
        }
        return true;
    }

    // ── Exportar / importar ──

    /** Gera o código do perfil: {@code CC1:} + gzip em Base64 (URL-safe) de um YAML com todos os arquivos. */
    public Exported export(String profile) throws IOException {
        YamlConfiguration doc = new YamlConfiguration();
        doc.set("formato", 1);
        doc.set("perfil", profile);
        doc.set("versao-plugin", plugin.getPluginMeta().getVersion());
        Set<String> needed = new LinkedHashSet<>();
        List<Map<String, String>> files = new ArrayList<>();
        for (CreationType type : CreationType.values()) {
            File[] list = dir(profile, type).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
            if (list == null) {
                continue;
            }
            Arrays.sort(list, Comparator.comparing(File::getName));
            for (File file : list) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!FILE_PATH.matcher(folder(type) + "/" + name).matches()) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("caminho", folder(type) + "/" + name);
                entry.put("conteudo", Files.readString(file.toPath(), StandardCharsets.UTF_8));
                files.add(entry);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                for (String id : yaml.getKeys(false)) {
                    ConfigurationSection s = yaml.getConfigurationSection(id);
                    if (s != null && s.getString("modelo") != null && type != CreationType.BLOCK) {
                        needed.add("models/" + s.getString("modelo"));
                    }
                    if (s != null && s.getString("armadura.textura") != null) {
                        needed.add("armor/" + s.getString("armadura.textura"));
                    }
                }
            }
        }
        doc.set("arquivos", files);
        doc.set("necessarios", new ArrayList<>(needed));
        byte[] raw = doc.saveToString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(raw);
        }
        return new Exported(CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()),
                List.copyOf(needed));
    }

    /** Pasta onde os códigos exportados são gravados como arquivo. */
    public File exportDir() {
        File dir = new File(root, "_exportados");
        dir.mkdirs();
        return dir;
    }

    /** Importa um código como um perfil novo (se o nome já existir, ganha sufixo _2, _3...). */
    public Imported importCode(String code, String nameOverride) throws IOException {
        String text = code.strip();
        if (!text.startsWith(CODE_PREFIX)) {
            throw new IOException("não é um código do CreatorCore (deve começar com " + CODE_PREFIX + ")");
        }
        byte[] packed;
        try {
            packed = Base64.getUrlDecoder().decode(text.substring(CODE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IOException("código corrompido (faltou copiar um pedaço?)");
        }
        String yamlText;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] data = gzip.readNBytes((int) MAX_CODE_BYTES + 1);
            if (data.length > MAX_CODE_BYTES) {
                throw new IOException("código grande demais");
            }
            yamlText = new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("código corrompido ou incompleto");
        }
        YamlConfiguration doc = new YamlConfiguration();
        try {
            doc.loadFromString(yamlText);
        } catch (InvalidConfigurationException e) {
            throw new IOException("conteúdo do código inválido");
        }
        if (doc.getInt("formato", 0) != 1) {
            throw new IOException("formato de código não suportado (atualize o CreatorCore)");
        }
        String base = nameOverride != null ? nameOverride : doc.getString("perfil", "importado");
        base = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.isBlank()) {
            base = "importado";
        }
        String name = base;
        for (int n = 2; exists(name); n++) {
            name = base + "_" + n;
        }
        create(name);
        int[] counts = new int[3];
        List<String> duplicates = new ArrayList<>();
        for (Map<?, ?> entry : doc.getMapList("arquivos")) {
            Object path = entry.get("caminho");
            Object content = entry.get("conteudo");
            if (path instanceof String p && content instanceof String c) {
                writeFile(p, c, name, counts, duplicates);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String need : doc.getStringList("necessarios")) {
            boolean ok = need.startsWith("models/")
                    ? plugin.models().has(need.substring("models/".length()))
                    : plugin.models().hasArmor(need.substring("armor/".length()));
            if (!ok) {
                missing.add(need);
            }
        }
        return new Imported(name, counts[0], counts[1], counts[2], missing, duplicates);
    }

    /** Grava um arquivo do código; só aceita caminhos {@code items|blocks|mobs/<id>.yml} (nada fora do perfil). */
    private void writeFile(String relative, String content, String profile, int[] counts, List<String> duplicates) throws IOException {
        if (!FILE_PATH.matcher(relative).matches() || content.length() > 512 * 1024) {
            plugin.getLogger().warning("Importação: arquivo '" + relative + "' ignorado.");
            return;
        }
        CreationType type = switch (relative.substring(0, relative.indexOf('/'))) {
            case "items" -> CreationType.ITEM;
            case "blocks" -> CreationType.BLOCK;
            default -> CreationType.MOB;
        };
        File target = new File(new File(root, profile), relative);
        target.getParentFile().mkdirs();
        Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
        var keys = YamlConfiguration.loadConfiguration(target).getKeys(false);
        counts[type == CreationType.ITEM ? 0 : type == CreationType.BLOCK ? 1 : 2] += keys.size();
        for (String id : keys) {
            if (plugin.registry().get(type, id).isPresent()) {
                duplicates.add(id);
            }
        }
    }
}
