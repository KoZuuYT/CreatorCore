package dev.creatorcore.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Converte modelos de criação (projeto do Blockbench {@code .bbmodel} ou geometria Bedrock/GeckoLib
 * {@code .geo.json}) para o formato Java Block/Item do Minecraft.
 *
 * <p>O jogo só aceita, por elemento, uma rotação de um eixo em passos de 22,5° (até 45°). Cubos que precisam
 * de mais (vários eixos, ângulos livres, grupos aninhados girados) são <b>adaptados</b>: o cubo é fatiado em
 * pequenas células que são giradas de verdade e remontadas sem rotação, mantendo a forma e a posição
 * (com borda levemente serrilhada) e a textura no lugar certo (a orientação da textura é aproximada).
 * Animações não são importadas (o modelo fica na pose do arquivo).
 */
public final class ModelImporter {

    private static final long MAX_TEXTURE_BYTES = 2L * 1024 * 1024;
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};
    private static final double[] ANGLES = {-45, -22.5, 22.5, 45};
    /** Máximo de células por cubo adaptado e de elementos por modelo. */
    private static final int MAX_CELLS_PER_CUBE = 216;
    private static final int MAX_ELEMENTS = 3000;

    /** Modelo convertido: o JSON do modelo e suas texturas (nome do arquivo sem extensão -> PNG). */
    public record Imported(String id, String modelJson, Map<String, byte[]> textures, Rig rig) {
    }

    /** Uma rotação em torno de um ponto (graus, convenção do Blockbench: aplica X, depois Y, depois Z). */
    private record Rot(double[] origin, double[] angles) {
        boolean zero() {
            return angles[0] == 0 && angles[1] == 0 && angles[2] == 0;
        }
    }

    /** Uma face com o retângulo de UV em pixels da textura. */
    private record Face(int texture, double[] uv, int rotation) {
    }

    /**
     * Cubo com as rotações a aplicar, da mais interna (do cubo) à mais externa (grupo raiz).
     *
     * @param bone     chave do osso/grupo a que pertence
     * @param ownCount quantas rotações do início da cadeia são do próprio cubo (o resto vem dos ossos)
     */
    private record Cube(String name, double[] from, double[] to, List<Rot> chain, Map<String, Face> faces,
                        String bone, int ownCount) {
    }

    /** Osso em construção (antes da ordenação pai-antes-do-filho). */
    private record RawBone(String key, String parent, double[] pivot, double[] bind) {
    }

    private ModelImporter() {
    }

    // ── Blockbench (.bbmodel) ───────────────────────────────────

    /** @throws IllegalArgumentException se o arquivo não for utilizável */
    public static Imported convert(String id, JsonObject project, String namespace, Logger log) {
        int resW = 16;
        int resH = 16;
        if (project.has("resolution") && project.get("resolution").isJsonObject()) {
            JsonObject res = project.getAsJsonObject("resolution");
            resW = res.has("width") ? Math.max(1, res.get("width").getAsInt()) : 16;
            resH = res.has("height") ? Math.max(1, res.get("height").getAsInt()) : 16;
        }
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
            if (!validPng(png)) {
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

        // Rotações dos grupos (do mais próximo ao mais externo), por uuid de elemento.
        Map<String, List<Rot>> groupChains = new LinkedHashMap<>();
        List<RawBone> rawBones = new ArrayList<>();
        Map<String, String> elementBone = new LinkedHashMap<>();
        if (project.has("outliner")) {
            for (JsonElement node : project.getAsJsonArray("outliner")) {
                walk(node, new ArrayList<>(), null, groupChains, rawBones, elementBone);
            }
        }

        List<Cube> cubes = new ArrayList<>();
        int skipped = 0;
        JsonArray elements = project.has("elements") ? project.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement raw : elements) {
            JsonObject e = raw.getAsJsonObject();
            if (!(e.has("type") ? e.get("type").getAsString() : "cube").equals("cube")) {
                skipped++;
                continue;
            }
            double[] from = vec(e.get("from"), 3);
            double[] to = vec(e.get("to"), 3);
            double inflate = e.has("inflate") ? e.get("inflate").getAsDouble() : 0;
            for (int i = 0; i < 3; i++) {
                from[i] -= inflate;
                to[i] += inflate;
            }
            List<Rot> chain = new ArrayList<>();
            double[] rotation = e.has("rotation") ? vec(e.get("rotation"), 3) : new double[3];
            double[] origin = e.has("origin") ? vec(e.get("origin"), 3) : new double[3];
            addIfRotated(chain, origin, rotation);
            int ownCount = chain.size();
            String uuid = e.has("uuid") ? e.get("uuid").getAsString() : null;
            if (uuid != null && groupChains.containsKey(uuid)) {
                chain.addAll(groupChains.get(uuid));
            }
            String bone = uuid != null && elementBone.containsKey(uuid) ? elementBone.get(uuid) : ROOT_BONE;
            Map<String, Face> faces = new LinkedHashMap<>();
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
                double[] uv = f.has("uv") ? vec(f.get("uv"), 4) : new double[] {0, 0, resW, resH};
                int faceRotation = f.has("rotation") ? ((f.get("rotation").getAsInt() % 360) + 360) % 360 : 0;
                faces.put(face, new Face(f.get("texture").getAsInt(), uv, faceRotation));
            }
            cubes.add(new Cube(e.has("name") ? e.get("name").getAsString() : "?", from, to, chain, faces,
                    bone, ownCount));
        }
        if (skipped > 0) {
            log.warning("Modelo '" + id + "': " + skipped + " elemento(s) que não são cubos (malhas etc.) foram ignorados.");
        }
        JsonObject display = project.has("display") && project.get("display").isJsonObject()
                ? project.getAsJsonObject("display") : null;
        Map<String, Rig.Animation> animations = new LinkedHashMap<>();
        if (project.has("animations") && project.get("animations").isJsonArray()) {
            for (JsonElement a : project.getAsJsonArray("animations")) {
                try {
                    Rig.Animation animation = parseBbAnimation(a.getAsJsonObject());
                    animations.put(animation.name(), animation);
                } catch (RuntimeException ex) {
                    log.warning("Modelo '" + id + "': uma animação foi ignorada (" + ex.getMessage() + ").");
                }
            }
        }
        return build(id, cubes, rawBones, animations, resW, resH, textureRefs, pngs, display, namespace, log);
    }

    private static final String ROOT_BONE = "__raiz";

    /** Percorre o outliner: cada filho (uuid) recebe a cadeia de rotações dos grupos pais e o osso do grupo. */
    private static void walk(JsonElement node, List<Rot> parents, String parentBone, Map<String, List<Rot>> out,
                             List<RawBone> bones, Map<String, String> elementBone) {
        if (node.isJsonPrimitive()) {
            out.put(node.getAsString(), parents);
            elementBone.put(node.getAsString(), parentBone == null ? ROOT_BONE : parentBone);
            return;
        }
        JsonObject group = node.getAsJsonObject();
        String key = group.has("uuid") ? group.get("uuid").getAsString() : "g" + bones.size();
        double[] org = group.has("origin") ? vec(group.get("origin"), 3) : new double[3];
        double[] rot = group.has("rotation") ? vec(group.get("rotation"), 3) : new double[3];
        bones.add(new RawBone(key, parentBone, org, rot));
        // chain = do mais interno (este grupo) ao mais externo: este grupo vem antes dos pais
        List<Rot> chain = new ArrayList<>();
        addIfRotated(chain, org, rot);
        chain.addAll(parents);
        if (group.has("children")) {
            for (JsonElement child : group.getAsJsonArray("children")) {
                walk(child, chain, key, out, bones, elementBone);
            }
        }
    }

    private static Rig.Animation parseBbAnimation(JsonObject a) {
        String name = a.has("name") ? a.get("name").getAsString() : "animacao";
        double length = a.has("length") ? number(a.get("length")) : 0;
        boolean loop = a.has("loop") && a.get("loop").getAsString().equals("loop");
        Map<String, Rig.BoneAnim> bones = new LinkedHashMap<>();
        if (a.has("animators") && a.get("animators").isJsonObject()) {
            for (var entry : a.getAsJsonObject("animators").entrySet()) {
                if (!entry.getValue().isJsonObject() || !entry.getValue().getAsJsonObject().has("keyframes")) {
                    continue;
                }
                List<Rig.Keyframe> rotation = new ArrayList<>();
                List<Rig.Keyframe> position = new ArrayList<>();
                for (JsonElement k : entry.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                    JsonObject frame = k.getAsJsonObject();
                    String channel = frame.has("channel") ? frame.get("channel").getAsString() : "";
                    if (!channel.equals("rotation") && !channel.equals("position")
                            || !frame.has("data_points") || frame.getAsJsonArray("data_points").isEmpty()) {
                        continue;
                    }
                    JsonObject point = frame.getAsJsonArray("data_points").get(0).getAsJsonObject();
                    double[] value = {point.has("x") ? number(point.get("x")) : 0,
                            point.has("y") ? number(point.get("y")) : 0, point.has("z") ? number(point.get("z")) : 0};
                    (channel.equals("rotation") ? rotation : position)
                            .add(new Rig.Keyframe(frame.has("time") ? number(frame.get("time")) : 0, value));
                    length = Math.max(length, frame.has("time") ? number(frame.get("time")) : 0);
                }
                rotation.sort(java.util.Comparator.comparingDouble(Rig.Keyframe::time));
                position.sort(java.util.Comparator.comparingDouble(Rig.Keyframe::time));
                bones.put(entry.getKey(), new Rig.BoneAnim(List.copyOf(rotation), List.copyOf(position)));
            }
        }
        return new Rig.Animation(name, length, loop, bones);
    }

    /** Número ou texto numérico; fórmulas (Molang) viram 0. */
    private static double number(JsonElement element) {
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ── Geometria Bedrock / GeckoLib (.geo.json) ────────────────

    /**
     * @param texturePng PNG da textura (arquivo irmão {@code <id>.png})
     * @throws IllegalArgumentException se o arquivo não for utilizável
     */
    public static Imported convertGeo(String id, JsonObject geo, JsonObject animationFile, byte[] texturePng,
                                      String namespace, Logger log) {
        if (!validPng(texturePng)) {
            throw new IllegalArgumentException("a textura " + id + ".png não é um PNG válido (ou é grande demais)");
        }
        if (!geo.has("minecraft:geometry") || geo.getAsJsonArray("minecraft:geometry").isEmpty()) {
            throw new IllegalArgumentException("só o formato 1.12+ com \"minecraft:geometry\" é suportado");
        }
        JsonObject geometry = geo.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        int resW = 64;
        int resH = 64;
        if (geometry.has("description")) {
            JsonObject d = geometry.getAsJsonObject("description");
            resW = d.has("texture_width") ? Math.max(1, d.get("texture_width").getAsInt()) : 64;
            resH = d.has("texture_height") ? Math.max(1, d.get("texture_height").getAsInt()) : 64;
        }
        Map<String, JsonObject> bones = new LinkedHashMap<>();
        JsonArray boneArray = geometry.has("bones") ? geometry.getAsJsonArray("bones") : new JsonArray();
        for (JsonElement b : boneArray) {
            bones.put(b.getAsJsonObject().get("name").getAsString(), b.getAsJsonObject());
        }
        List<Cube> cubes = new ArrayList<>();
        List<RawBone> rawBones = new ArrayList<>();
        for (JsonObject bone : bones.values()) {
            rawBones.add(new RawBone(bone.get("name").getAsString(),
                    bone.has("parent") && bones.containsKey(bone.get("parent").getAsString())
                            ? bone.get("parent").getAsString() : null,
                    geoPoint(bone.has("pivot") ? vec(bone.get("pivot"), 3) : new double[3]),
                    geoAngles(bone.has("rotation") ? vec(bone.get("rotation"), 3) : new double[3])));
            if (!bone.has("cubes")) {
                continue;
            }
            // Cadeia de rotações dos ossos: do osso do cubo até a raiz.
            List<Rot> boneChain = new ArrayList<>();
            JsonObject current = bone;
            for (int guard = 0; current != null && guard < 64; guard++) {
                addIfRotated(boneChain, geoPoint(current.has("pivot") ? vec(current.get("pivot"), 3) : new double[3]),
                        geoAngles(current.has("rotation") ? vec(current.get("rotation"), 3) : new double[3]));
                current = current.has("parent") ? bones.get(current.get("parent").getAsString()) : null;
            }
            for (JsonElement ce : bone.getAsJsonArray("cubes")) {
                JsonObject c = ce.getAsJsonObject();
                double[] origin = vec(c.get("origin"), 3);
                double[] size = vec(c.get("size"), 3);
                double inflate = c.has("inflate") ? c.get("inflate").getAsDouble() : 0;
                double[] from = geoPoint(origin);
                double[] to = {from[0] + size[0], from[1] + size[1], from[2] + size[2]};
                for (int i = 0; i < 3; i++) {
                    from[i] -= inflate;
                    to[i] += inflate;
                }
                List<Rot> chain = new ArrayList<>();
                if (c.has("rotation")) {
                    double[] pivot = c.has("pivot") ? vec(c.get("pivot"), 3) : origin;
                    addIfRotated(chain, geoPoint(pivot), geoAngles(vec(c.get("rotation"), 3)));
                }
                int ownCount = chain.size();
                chain.addAll(boneChain);
                cubes.add(new Cube(bone.get("name").getAsString(), from, to, chain, geoFaces(c, size),
                        bone.get("name").getAsString(), ownCount));
            }
        }
        Map<String, byte[]> pngs = new LinkedHashMap<>();
        pngs.put(id + "_0", texturePng);
        Map<Integer, String> refs = new LinkedHashMap<>();
        refs.put(0, id + "_0");
        Map<String, Rig.Animation> animations = new LinkedHashMap<>();
        if (animationFile != null && animationFile.has("animations") && animationFile.get("animations").isJsonObject()) {
            for (var entry : animationFile.getAsJsonObject("animations").entrySet()) {
                try {
                    String name = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1);
                    animations.put(name, parseGeoAnimation(name, entry.getValue().getAsJsonObject()));
                } catch (RuntimeException ex) {
                    log.warning("Modelo '" + id + "': a animação '" + entry.getKey() + "' foi ignorada (" + ex.getMessage() + ").");
                }
            }
        }
        return build(id, cubes, rawBones, animations, resW, resH, refs, pngs, null, namespace, log);
    }

    private static Rig.Animation parseGeoAnimation(String name, JsonObject a) {
        double length = a.has("animation_length") ? number(a.get("animation_length")) : 0;
        boolean loop = a.has("loop") && a.get("loop").isJsonPrimitive() && a.get("loop").getAsString().equals("true");
        Map<String, Rig.BoneAnim> bones = new LinkedHashMap<>();
        if (a.has("bones") && a.get("bones").isJsonObject()) {
            for (var entry : a.getAsJsonObject("bones").entrySet()) {
                JsonObject channels = entry.getValue().getAsJsonObject();
                List<Rig.Keyframe> rotation = geoKeyframes(channels.get("rotation"), true);
                List<Rig.Keyframe> position = geoKeyframes(channels.get("position"), false);
                for (var list : List.of(rotation, position)) {
                    if (!list.isEmpty()) {
                        length = Math.max(length, list.get(list.size() - 1).time());
                    }
                }
                bones.put(entry.getKey(), new Rig.BoneAnim(rotation, position));
            }
        }
        return new Rig.Animation(name, length, loop, bones);
    }

    /** Canal Bedrock: vetor fixo, ou mapa tempo -> vetor ({@code post} se for objeto). */
    private static List<Rig.Keyframe> geoKeyframes(JsonElement channel, boolean rotation) {
        List<Rig.Keyframe> frames = new ArrayList<>();
        if (channel == null || channel.isJsonNull()) {
            return frames;
        }
        if (channel.isJsonArray()) {
            frames.add(new Rig.Keyframe(0, geoVector(channel, rotation)));
        } else if (channel.isJsonObject()) {
            for (var entry : channel.getAsJsonObject().entrySet()) {
                double time;
                try {
                    time = Double.parseDouble(entry.getKey());
                } catch (NumberFormatException e) {
                    continue; // chave que não é tempo (ex.: "vector", "lerp_mode")
                }
                JsonElement value = entry.getValue();
                if (value.isJsonObject()) {
                    JsonObject o = value.getAsJsonObject();
                    value = o.has("post") ? o.get("post") : o.get("pre");
                }
                if (value != null && value.isJsonArray()) {
                    frames.add(new Rig.Keyframe(time, geoVector(value, rotation)));
                }
            }
        }
        frames.sort(java.util.Comparator.comparingDouble(Rig.Keyframe::time));
        return frames;
    }

    private static double[] geoVector(JsonElement array, boolean rotation) {
        JsonArray a = array.getAsJsonArray();
        double[] v = {a.size() > 0 ? number(a.get(0)) : 0, a.size() > 1 ? number(a.get(1)) : 0,
                a.size() > 2 ? number(a.get(2)) : 0};
        // Bedrock -> convenção interna: gira com X e Y invertidos; posição com X invertido.
        return rotation ? new double[] {-v[0], -v[1], v[2]} : new double[] {-v[0], v[1], v[2]};
    }

    /** Bedrock: origem no centro dos pés (x, z) -> modelo do jogo, de 0 a 16. */
    private static double[] geoPoint(double[] p) {
        return new double[] {p[0] + 8, p[1], p[2] + 8};
    }

    /** Bedrock inverte o sentido dos eixos X e Y em relação ao Blockbench. */
    private static double[] geoAngles(double[] r) {
        return new double[] {-r[0], -r[1], r[2]};
    }

    private static Map<String, Face> geoFaces(JsonObject c, double[] size) {
        Map<String, Face> faces = new LinkedHashMap<>();
        double w = size[0];
        double h = size[1];
        double d = size[2];
        if (c.has("uv") && c.get("uv").isJsonObject()) {
            JsonObject perFace = c.getAsJsonObject("uv");
            for (String face : FACES) {
                if (perFace.has(face) && perFace.get(face).isJsonObject()) {
                    JsonObject f = perFace.getAsJsonObject(face);
                    double[] uv = vec(f.get("uv"), 2);
                    double[] sz = f.has("uv_size") ? vec(f.get("uv_size"), 2) : new double[] {0, 0};
                    faces.put(face, new Face(0, new double[] {uv[0], uv[1], uv[0] + sz[0], uv[1] + sz[1]}, 0));
                }
            }
            return faces;
        }
        double[] uv = c.has("uv") ? vec(c.get("uv"), 2) : new double[] {0, 0};
        double u = uv[0];
        double v = uv[1];
        faces.put("east", new Face(0, new double[] {u, v + d, u + d, v + d + h}, 0));
        faces.put("north", new Face(0, new double[] {u + d, v + d, u + d + w, v + d + h}, 0));
        faces.put("west", new Face(0, new double[] {u + d + w, v + d, u + d + w + d, v + d + h}, 0));
        faces.put("south", new Face(0, new double[] {u + d + w + d, v + d, u + d + w + d + w, v + d + h}, 0));
        faces.put("up", new Face(0, new double[] {u + d, v, u + d + w, v + d}, 0));
        faces.put("down", new Face(0, new double[] {u + d + w, v + d, u + d + w + w, v}, 0));
        return faces;
    }

    // ── Construção do modelo do jogo ────────────────────────────

    private static Imported build(String id, List<Cube> cubes, List<RawBone> rawBones,
                                  Map<String, Rig.Animation> animations, int resW, int resH,
                                  Map<Integer, String> textureRefs, Map<String, byte[]> pngs, JsonObject display,
                                  String namespace, Logger log) {
        JsonObject textureMap = new JsonObject();
        for (var entry : textureRefs.entrySet()) {
            textureMap.addProperty("t" + entry.getKey(), namespace + ":item/" + entry.getValue());
        }
        textureMap.addProperty("particle", namespace + ":item/" + textureRefs.values().iterator().next());

        // Modelo único (itens e mobs sem animação): todas as rotações dos ossos já aplicadas.
        int[] adapted = {0};
        JsonArray whole = elements(id, cubes, false, resW, resH, adapted, log);
        if (whole.isEmpty()) {
            throw new IllegalArgumentException("o modelo não tem cubos (malhas e outros tipos não são suportados)");
        }
        if (whole.size() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("o modelo ficou com " + whole.size() + " elementos (máx. " + MAX_ELEMENTS
                    + "); simplifique as rotações");
        }
        if (adapted[0] > 0) {
            log.info("Modelo '" + id + "': " + adapted[0] + " cubo(s) com rotação não suportada pelo jogo foram adaptados ("
                    + whole.size() + " elementos no total).");
        }
        String wholeJson = modelJson(textureMap, whole, display);

        // Esqueleto: um modelo por osso, só com os cubos dele (as rotações dos ossos ficam por conta da animação).
        Rig rig = null;
        if (!animations.isEmpty()) {
            List<Rig.Bone> ordered = orderBones(rawBones, cubes);
            Map<String, String> boneModels = new LinkedHashMap<>();
            int[] ignored = {0};
            for (Rig.Bone bone : ordered) {
                List<Cube> own = cubes.stream().filter(c -> c.bone().equals(bone.key())).toList();
                if (own.isEmpty()) {
                    continue;
                }
                boneModels.put(bone.key(), modelJson(textureMap, elements(id, own, true, resW, resH, ignored, log), null));
            }
            rig = new Rig(List.copyOf(ordered), boneModels, Map.copyOf(animations));
            log.info("Modelo '" + id + "': " + animations.size() + " animação(ões) (" + String.join(", ", animations.keySet())
                    + "), " + boneModels.size() + " parte(s) animada(s).");
        }
        return new Imported(id, wholeJson, pngs, rig);
    }

    private static JsonArray elements(String id, List<Cube> cubes, boolean ownOnly, int resW, int resH, int[] adapted,
                                      Logger log) {
        JsonArray out = new JsonArray();
        for (Cube cube : cubes) {
            if (!ownOnly && (outOfRange(cube.from()) || outOfRange(cube.to()))) {
                log.warning("Modelo '" + id + "': cubo '" + cube.name() + "' passa dos limites do jogo (-16 a 32); o jogo pode recortá-lo.");
            }
            List<Rot> chain = ownOnly ? cube.chain().subList(0, cube.ownCount()) : cube.chain();
            if (chain.isEmpty()) {
                out.add(element(cube.from(), cube.to(), null, faces(cube.faces(), resW, resH)));
            } else if (chain.size() == 1 && nativeRotation(chain.get(0)) != null) {
                out.add(element(cube.from(), cube.to(), nativeRotation(chain.get(0)), faces(cube.faces(), resW, resH)));
            } else {
                adapted[0]++;
                voxelize(new Cube(cube.name(), cube.from(), cube.to(), chain, cube.faces(), cube.bone(), cube.ownCount()),
                        resW, resH, out);
            }
        }
        return out;
    }

    private static String modelJson(JsonObject textures, JsonArray elements, JsonObject display) {
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        if (display != null) {
            model.add("display", display);
        }
        return model.toString();
    }

    /** Ossos com pai antes do filho; o osso raiz implícito do bbmodel entra se algum cubo o usar. */
    private static List<Rig.Bone> orderBones(List<RawBone> raw, List<Cube> cubes) {
        List<RawBone> all = new ArrayList<>(raw);
        if (cubes.stream().anyMatch(c -> c.bone().equals(ROOT_BONE))) {
            all.add(0, new RawBone(ROOT_BONE, null, new double[3], new double[3]));
        }
        List<Rig.Bone> ordered = new ArrayList<>();
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (int pass = 0; pass < all.size() + 1 && ordered.size() < all.size(); pass++) {
            for (RawBone b : all) {
                if (!placed.contains(b.key()) && (b.parent() == null || placed.contains(b.parent()))) {
                    ordered.add(new Rig.Bone(b.key(), b.parent(), b.pivot(), b.bind()));
                    placed.add(b.key());
                }
            }
        }
        return ordered;
    }

    private static JsonObject element(double[] from, double[] to, JsonObject rotation, JsonObject faces) {
        JsonObject cube = new JsonObject();
        cube.add("from", arr(from));
        cube.add("to", arr(to));
        if (rotation != null) {
            cube.add("rotation", rotation);
        }
        cube.add("faces", faces);
        return cube;
    }

    private static JsonObject faces(Map<String, Face> faces, int resW, int resH) {
        JsonObject out = new JsonObject();
        for (var entry : faces.entrySet()) {
            Face f = entry.getValue();
            JsonObject jf = new JsonObject();
            jf.add("uv", arr(new double[] {f.uv()[0] * 16 / resW, f.uv()[1] * 16 / resH,
                    f.uv()[2] * 16 / resW, f.uv()[3] * 16 / resH}));
            jf.addProperty("texture", "#t" + f.texture());
            if (f.rotation() != 0) {
                jf.addProperty("rotation", f.rotation());
            }
            out.add(entry.getKey(), jf);
        }
        return out;
    }

    /** A rotação que o jogo aceita: um eixo, múltiplo de 22,5° até 45° (tolerância de 0,01°). */
    private static JsonObject nativeRotation(Rot rot) {
        int axisIndex = -1;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(rot.angles()[i]) > 0.01) {
                if (axisIndex >= 0) {
                    return null;
                }
                axisIndex = i;
            }
        }
        if (axisIndex < 0) {
            return null;
        }
        double angle = rot.angles()[axisIndex];
        for (double allowed : ANGLES) {
            if (Math.abs(allowed - angle) <= 0.01) {
                JsonObject r = new JsonObject();
                r.add("origin", arr(rot.origin()));
                r.addProperty("axis", new String[] {"x", "y", "z"}[axisIndex]);
                r.addProperty("angle", allowed);
                return r;
            }
        }
        return null;
    }

    // ── Adaptação: células giradas ──────────────────────────────

    /** Como o jogo mapeia as UVs: eixo (0=x,1=y,2=z) e sentido de u e v em cada face. */
    private static final Map<String, int[]> FACE_FRAME = Map.of(
            "north", new int[] {0, -1, 1, -1},
            "south", new int[] {0, 1, 1, -1},
            "west", new int[] {2, 1, 1, -1},
            "east", new int[] {2, -1, 1, -1},
            "up", new int[] {0, 1, 2, 1},
            "down", new int[] {0, 1, 2, -1});
    private static final Map<String, double[]> NORMALS = Map.of(
            "north", new double[] {0, 0, -1}, "south", new double[] {0, 0, 1},
            "west", new double[] {-1, 0, 0}, "east", new double[] {1, 0, 0},
            "up", new double[] {0, 1, 0}, "down", new double[] {0, -1, 0});

    private static void voxelize(Cube cube, int resW, int resH, JsonArray out) {
        double[] size = new double[3];
        for (int i = 0; i < 3; i++) {
            size[i] = cube.to()[i] - cube.from()[i];
        }
        // Tamanho da célula: começa em 1 unidade e cresce até caber no limite de células.
        double cell = 1.0;
        int[] n = new int[3];
        for (int guard = 0; guard < 40; guard++) {
            long total = 1;
            for (int i = 0; i < 3; i++) {
                n[i] = Math.max(1, (int) Math.ceil(size[i] / cell - 1e-9));
                total *= n[i];
            }
            if (total <= MAX_CELLS_PER_CUBE) {
                break;
            }
            cell *= 1.25;
        }
        double[] d = {size[0] / n[0], size[1] / n[1], size[2] / n[2]};
        double[][] linear = linearPart(cube.chain());
        // Face original que fica voltada para cada direção do mundo depois de girar.
        Map<String, String> sourceFace = new LinkedHashMap<>();
        for (String face : FACES) {
            double best = -2;
            String chosen = face;
            for (String original : FACES) {
                double[] rotated = apply(linear, NORMALS.get(original));
                double dot = rotated[0] * NORMALS.get(face)[0] + rotated[1] * NORMALS.get(face)[1]
                        + rotated[2] * NORMALS.get(face)[2];
                if (dot > best) {
                    best = dot;
                    chosen = original;
                }
            }
            sourceFace.put(face, chosen);
        }
        for (int ix = 0; ix < n[0]; ix++) {
            for (int iy = 0; iy < n[1]; iy++) {
                for (int iz = 0; iz < n[2]; iz++) {
                    int[] index = {ix, iy, iz};
                    double[] center = new double[3];
                    for (int i = 0; i < 3; i++) {
                        center[i] = cube.from()[i] + (index[i] + 0.5) * d[i];
                    }
                    JsonObject faces = new JsonObject();
                    for (String face : FACES) {
                        String original = sourceFace.get(face);
                        Face source = cube.faces().get(original);
                        if (source == null || !onSide(original, index, n)) {
                            continue;
                        }
                        faces.add(face, cellFace(source, FACE_FRAME.get(original), center, d, cube, resW, resH));
                    }
                    if (faces.size() == 0) {
                        continue;
                    }
                    double[] moved = center;
                    for (Rot rot : cube.chain()) {
                        moved = rotate(moved, rot);
                    }
                    // Leve folga para as células girando não deixarem frestas entre si.
                    double grow = 1.15;
                    double[] from = new double[3];
                    double[] to = new double[3];
                    for (int i = 0; i < 3; i++) {
                        from[i] = moved[i] - d[i] * grow / 2;
                        to[i] = moved[i] + d[i] * grow / 2;
                    }
                    out.add(element(from, to, null, faces));
                }
            }
        }
    }

    /** A célula toca a face {@code original} do cubo? */
    private static boolean onSide(String original, int[] index, int[] n) {
        return switch (original) {
            case "west" -> index[0] == 0;
            case "east" -> index[0] == n[0] - 1;
            case "down" -> index[1] == 0;
            case "up" -> index[1] == n[1] - 1;
            case "north" -> index[2] == 0;
            default -> index[2] == n[2] - 1;
        };
    }

    /** UV da célula: o trecho da face original em que a célula estava (sem girar a textura). */
    private static JsonObject cellFace(Face source, int[] frame, double[] center, double[] d, Cube cube,
                                       int resW, int resH) {
        double[] uv = source.uv();
        double[] fractions = new double[2];
        double[] halves = new double[2];
        for (int k = 0; k < 2; k++) {
            int axis = frame[k * 2];
            int sign = frame[k * 2 + 1];
            double extent = cube.to()[axis] - cube.from()[axis];
            if (extent <= 1e-9) {
                fractions[k] = 0.5;
                halves[k] = 0.5;
            } else {
                double along = (center[axis] - cube.from()[axis]) / extent;
                fractions[k] = sign > 0 ? along : 1 - along;
                halves[k] = d[axis] / extent / 2;
            }
        }
        double u0 = uv[0] + (fractions[0] - halves[0]) * (uv[2] - uv[0]);
        double u1 = uv[0] + (fractions[0] + halves[0]) * (uv[2] - uv[0]);
        double v0 = uv[1] + (fractions[1] - halves[1]) * (uv[3] - uv[1]);
        double v1 = uv[1] + (fractions[1] + halves[1]) * (uv[3] - uv[1]);
        JsonObject jf = new JsonObject();
        jf.add("uv", arr(new double[] {u0 * 16 / resW, v0 * 16 / resH, u1 * 16 / resW, v1 * 16 / resH}));
        jf.addProperty("texture", "#t" + source.texture());
        return jf;
    }

    // ── Matemática de rotações ──────────────────────────────────

    /** Gira o ponto em torno de {@code rot.origin}: R = Rz · Ry · Rx (X primeiro), sentido anti-horário. */
    private static double[] rotate(double[] p, Rot rot) {
        double[] local = {p[0] - rot.origin()[0], p[1] - rot.origin()[1], p[2] - rot.origin()[2]};
        double[] r = apply(matrix(rot.angles()), local);
        return new double[] {r[0] + rot.origin()[0], r[1] + rot.origin()[1], r[2] + rot.origin()[2]};
    }

    private static double[][] matrix(double[] degrees) {
        double[] a = {Math.toRadians(degrees[0]), Math.toRadians(degrees[1]), Math.toRadians(degrees[2])};
        double cx = Math.cos(a[0]);
        double sx = Math.sin(a[0]);
        double cy = Math.cos(a[1]);
        double sy = Math.sin(a[1]);
        double cz = Math.cos(a[2]);
        double sz = Math.sin(a[2]);
        double[][] rx = {{1, 0, 0}, {0, cx, -sx}, {0, sx, cx}};
        double[][] ry = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}};
        double[][] rz = {{cz, -sz, 0}, {sz, cz, 0}, {0, 0, 1}};
        return multiply(rz, multiply(ry, rx));
    }

    /** Parte linear (sem translação) de toda a cadeia de rotações. */
    private static double[][] linearPart(List<Rot> chain) {
        double[][] total = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (Rot rot : chain) {
            total = multiply(matrix(rot.angles()), total);
        }
        return total;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    r[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return r;
    }

    private static double[] apply(double[][] m, double[] v) {
        return new double[] {m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    // ── Utilidades ──────────────────────────────────────────────

    private static void addIfRotated(List<Rot> chain, double[] origin, double[] angles) {
        Rot rot = new Rot(origin, angles);
        if (!rot.zero()) {
            chain.add(rot);
        }
    }

    private static boolean validPng(byte[] png) {
        return png.length >= 8 && png.length <= MAX_TEXTURE_BYTES && (png[0] & 0xFF) == 0x89 && png[1] == 'P'
                && png[2] == 'N' && png[3] == 'G';
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
