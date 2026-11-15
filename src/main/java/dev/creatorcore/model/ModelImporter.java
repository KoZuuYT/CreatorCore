package dev.creatorcore.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Converte um projeto do Blockbench ({@code .bbmodel}) para um modelo Java Block/Item do Minecraft.
 *
 * <p>Suporta cubos (com UV por face e texturas embutidas), rotação de um único eixo em passos de 22,5°
 * (do cubo ou do grupo pai) e as transformações {@code display}. O que o formato do jogo não permite
 * (malhas, rotação em vários eixos, ângulos livres, animações) é ignorado com aviso.
 */
public final class ModelImporter {

    private static final long MAX_TEXTURE_BYTES = 2L * 1024 * 1024;
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};
    private static final double[] ANGLES = {-45, -22.5, 22.5, 45};

    /** Modelo convertido: o JSON do modelo e suas texturas (nome do arquivo sem extensão -> PNG). */
    public record Imported(String id, String modelJson, Map<String, byte[]> textures) {
    }

    private ModelImporter() {
    }

    /** @throws IllegalArgumentException se o arquivo não for utilizável */
    public static Imported convert(String id, JsonObject project, String namespace, Logger log) {
        int resW = 16;
        int resH = 16;
        if (project.has("resolution") && project.get("resolution").isJsonObject()) {
            JsonObject res = project.getAsJsonObject("resolution");
            resW = res.has("width") ? Math.max(1, res.get("width").getAsInt()) : 16;
            resH = res.has("height") ? Math.max(1, res.get("height").getAsInt()) : 16;
        }

        // Texturas: índice -> PNG
        Map<String, byte[]> pngs = new LinkedHashMap<>();
        Map<Integer, String> textureRefs = new LinkedHashMap<>();
        JsonArray textures = project.has("textures") ? project.getAsJsonArray("textures") : new JsonArray();
        for (int i = 0; i < textures.size(); i++) {
            JsonObject t = textures.get(i).getAsJsonObject();
            String source = t.has("source") && !t.get("source").isJsonNull() ? t.get("source").getAsString() : "";
            int comma = source.indexOf("base64,");
            if (comma < 0) {
                log.warning("Modelo '" + id + "': textura " + i + " sem imagem embutida (salve o projeto com as texturas); ignorada.");
                continue;
            }
            byte[] png;
            try {
                png = Base64.getDecoder().decode(source.substring(comma + 7));
            } catch (IllegalArgumentException e) {
                log.warning("Modelo '" + id + "': textura " + i + " corrompida; ignorada.");
                continue;
            }
            if (png.length > MAX_TEXTURE_BYTES || png.length < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P') {
                log.warning("Modelo '" + id + "': textura " + i + " não é um PNG válido (ou é grande demais); ignorada.");
                continue;
            }
            String name = id + "_" + i;
            pngs.put(name, png);
            textureRefs.put(i, name);
        }
        if (textureRefs.isEmpty()) {
            throw new IllegalArgumentException("o projeto não tem nenhuma textura embutida");
        }

        // Grupos: uuid do elemento -> rotação/origem do grupo mais próximo que gira
        Map<String, double[]> groupRotation = new LinkedHashMap<>();
        if (project.has("outliner")) {
            for (JsonElement node : project.getAsJsonArray("outliner")) {
                walk(node, null, groupRotation);
            }
        }

        JsonArray out = new JsonArray();
        int skipped = 0;
        JsonArray elements = project.has("elements") ? project.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement raw : elements) {
            JsonObject e = raw.getAsJsonObject();
            String type = e.has("type") ? e.get("type").getAsString() : "cube";
            if (!type.equals("cube")) {
                skipped++;
                continue;
            }
            JsonObject cube = new JsonObject();
            double[] from = vec(e.get("from"), 3);
            double[] to = vec(e.get("to"), 3);
            double inflate = e.has("inflate") ? e.get("inflate").getAsDouble() : 0;
            for (int i = 0; i < 3; i++) {
                from[i] -= inflate;
                to[i] += inflate;
            }
            if (outOfRange(from) || outOfRange(to)) {
                log.warning("Modelo '" + id + "': cubo '" + name(e) + "' passa dos limites do jogo (-16 a 32); o jogo pode recortá-lo.");
            }
            cube.add("from", arr(from));
            cube.add("to", arr(to));

            double[] rotation = e.has("rotation") ? vec(e.get("rotation"), 3) : new double[3];
            double[] origin = e.has("origin") ? vec(e.get("origin"), 3) : new double[3];
            if (isZero(rotation) && e.has("uuid") && groupRotation.containsKey(e.get("uuid").getAsString())) {
                double[] g = groupRotation.get(e.get("uuid").getAsString());
                rotation = new double[] {g[0], g[1], g[2]};
                origin = new double[] {g[3], g[4], g[5]};
            }
            if (!isZero(rotation)) {
                JsonObject rot = rotation(rotation, origin);
                if (rot == null) {
                    log.warning("Modelo '" + id + "': a rotação do cubo '" + name(e)
                            + "' não existe no jogo (use um eixo só, em passos de 22,5° até 45°); rotação ignorada.");
                } else {
                    cube.add("rotation", rot);
                }
            }

            JsonObject faces = new JsonObject();
            JsonObject bbFaces = e.has("faces") ? e.getAsJsonObject("faces") : new JsonObject();
            for (String face : FACES) {
                if (!bbFaces.has(face) || !bbFaces.get(face).isJsonObject()) {
                    continue;
                }
                JsonObject f = bbFaces.getAsJsonObject(face);
                if (!f.has("texture") || f.get("texture").isJsonNull()
                        || !textureRefs.containsKey(f.get("texture").getAsInt())) {
                    continue;
                }
                JsonObject jf = new JsonObject();
                double[] uv = f.has("uv") ? vec(f.get("uv"), 4) : new double[] {0, 0, 16, 16};
                jf.add("uv", arr(new double[] {uv[0] * 16 / resW, uv[1] * 16 / resH, uv[2] * 16 / resW, uv[3] * 16 / resH}));
                jf.addProperty("texture", "#t" + f.get("texture").getAsInt());
                if (f.has("rotation") && f.get("rotation").getAsInt() != 0) {
                    jf.addProperty("rotation", ((f.get("rotation").getAsInt() % 360) + 360) % 360);
                }
                faces.add(face, jf);
            }
            cube.add("faces", faces);
            out.add(cube);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("o projeto não tem cubos (malhas e outros tipos não são suportados)");
        }
        if (skipped > 0) {
            log.warning("Modelo '" + id + "': " + skipped + " elemento(s) que não são cubos foram ignorados.");
        }

        JsonObject model = new JsonObject();
        JsonObject texMap = new JsonObject();
        for (var entry : textureRefs.entrySet()) {
            texMap.addProperty("t" + entry.getKey(), namespace + ":item/" + entry.getValue());
        }
        texMap.addProperty("particle", namespace + ":item/" + textureRefs.values().iterator().next());
        model.add("textures", texMap);
        model.add("elements", out);
        if (project.has("display") && project.get("display").isJsonObject()) {
            model.add("display", project.getAsJsonObject("display"));
        }
        return new Imported(id, model.toString(), pngs);
    }

    /** Percorre o outliner: cada filho (uuid) herda a rotação do grupo pai mais próximo que gira. */
    private static void walk(JsonElement node, double[] inherited, Map<String, double[]> out) {
        if (node.isJsonPrimitive()) {
            if (inherited != null) {
                out.put(node.getAsString(), inherited);
            }
            return;
        }
        JsonObject group = node.getAsJsonObject();
        double[] current = inherited;
        if (group.has("rotation")) {
            double[] rot = vec(group.get("rotation"), 3);
            if (!isZero(rot)) {
                double[] org = group.has("origin") ? vec(group.get("origin"), 3) : new double[3];
                current = new double[] {rot[0], rot[1], rot[2], org[0], org[1], org[2]};
            }
        }
        if (group.has("children")) {
            for (JsonElement child : group.getAsJsonArray("children")) {
                walk(child, current, out);
            }
        }
    }

    /** Rotação do jogo: um eixo, em múltiplos de 22,5° (até 45°). */
    private static JsonObject rotation(double[] rot, double[] origin) {
        int axisIndex = -1;
        for (int i = 0; i < 3; i++) {
            if (rot[i] != 0) {
                if (axisIndex >= 0) {
                    return null;
                }
                axisIndex = i;
            }
        }
        double angle = rot[axisIndex];
        boolean ok = false;
        for (double allowed : ANGLES) {
            ok |= allowed == angle;
        }
        if (!ok) {
            return null;
        }
        JsonObject r = new JsonObject();
        r.add("origin", arr(origin));
        r.addProperty("axis", new String[] {"x", "y", "z"}[axisIndex]);
        r.addProperty("angle", angle);
        return r;
    }

    private static String name(JsonObject e) {
        return e.has("name") ? e.get("name").getAsString() : "?";
    }

    private static boolean isZero(double[] v) {
        return v[0] == 0 && v[1] == 0 && v[2] == 0;
    }

    private static boolean outOfRange(double[] v) {
        for (double d : v) {
            if (d < -16 || d > 32) {
                return true;
            }
        }
        return false;
    }

    private static double[] vec(JsonElement element, int size) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < size) {
            throw new IllegalArgumentException("vetor inválido no projeto (esperado " + size + " números)");
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = element.getAsJsonArray().get(i).getAsDouble();
        }
        return result;
    }

    private static JsonArray arr(double[] values) {
        JsonArray array = new JsonArray();
        for (double v : values) {
            array.add(v);
        }
        return array;
    }
}
