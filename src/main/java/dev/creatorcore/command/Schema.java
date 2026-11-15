package dev.creatorcore.command;

import dev.creatorcore.CreatorCorePlugin;
import dev.creatorcore.registry.CreationType;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * Campos editáveis de itens, blocos e mobs: o que aparece no editor, como cada valor é digitado e validado.
 * Os caminhos ({@code path}) são os mesmos do YAML (ex.: {@code comida.nutricao}).
 */
final class Schema {

    enum Kind { TEXT, INT, NUM, BOOL, ENUM, LIST, MAP, GRID }

    /** Converte o que o jogador digitou no valor guardado; lança {@link IllegalArgumentException} com o motivo. */
    interface Codec {
        Object parse(String input);

        /** Texto de uma entrada já guardada. */
        String show(Object entry);
    }

    /**
     * @param help    explicação em uma ou duas frases
     * @param example exemplo do que digitar (ou vazio)
     * @param check   validação extra do texto (devolve o erro ou {@code null}), ou {@code null}
     */
    record Field(String path, String label, Material icon, Kind kind, String help, String example, List<String> options,
                 double min, double max, Function<String, String> check, Codec codec) {
    }

    private Schema() {
    }

    // ── Construtores ──

    private static Field text(String path, String label, Material icon, String help, String example, Function<String, String> check) {
        return new Field(path, label, icon, Kind.TEXT, help, example, List.of(), 0, 0, check, null);
    }

    private static Field integer(String path, String label, Material icon, int min, int max, String help) {
        return new Field(path, label, icon, Kind.INT, help, String.valueOf(min), List.of(), min, max, null, null);
    }

    private static Field number(String path, String label, Material icon, double min, double max, String help) {
        return new Field(path, label, icon, Kind.NUM, help, String.valueOf(min), List.of(), min, max, null, null);
    }

    private static Field bool(String path, String label, Material icon, String help) {
        return new Field(path, label, icon, Kind.BOOL, help, "", List.of(), 0, 0, null, null);
    }

    private static Field choice(String path, String label, Material icon, String help, String... options) {
        return new Field(path, label, icon, Kind.ENUM, help, "", List.of(options), 0, 0, null, null);
    }

    private static Field list(String path, String label, Material icon, String help, String example, Codec codec) {
        return new Field(path, label, icon, Kind.LIST, help, example, List.of(), 0, 0, null, codec);
    }

    private static Field map(String path, String label, Material icon, String help, String example, Codec codec) {
        return new Field(path, label, icon, Kind.MAP, help, example, List.of(), 0, 0, null, codec);
    }

    private static Field grid(String path, String label, Material icon, String help) {
        return new Field(path, label, icon, Kind.GRID, help, "", List.of(), 0, 0, null, null);
    }

    // ── Campos por tipo ──

    static List<Field> fields(CreatorCorePlugin plugin, CreationType type) {
        return switch (type) {
            case ITEM -> item(plugin);
            case BLOCK -> block(plugin);
            case MOB -> mob(plugin);
        };
    }

    private static List<Field> item(CreatorCorePlugin plugin) {
        List<Field> f = new ArrayList<>();
        f.add(text("material", "Material base", Material.GRASS_BLOCK,
                "O item vanilla em que este se baseia (define o visual sem pack, o uso e a durabilidade).", "DIAMOND_SWORD", materialCheck()));
        f.add(text("nome", "Nome", Material.NAME_TAG, "Nome exibido. Aceita cores MiniMessage.", "<gold>Espada do Rei", null));
        f.add(list("lore", "Descrição (lore)", Material.WRITABLE_BOOK, "Linhas de texto abaixo do nome.", "<gray>Forjada em lava", rawLines()));
        f.add(text("modelo", "Modelo", Material.ITEM_FRAME,
                "Nome de um modelo da pasta models/ (Blockbench) ou do pack. Vazio = visual do material base.", "espada_rei",
                v -> CreatorCoreIds.valid(v) ? null : "use minúsculas, números e _"));
        f.add(bool("brilho", "Brilho de encantado", Material.GLOWSTONE_DUST, "Mostra o brilho de encantamento."));
        f.add(bool("inquebravel", "Inquebrável", Material.ANVIL, "O item nunca perde durabilidade."));
        f.add(integer("durabilidade", "Durabilidade máxima", Material.IRON_INGOT, 1, 100000, "Usos até quebrar (só para itens que gastam)."));
        f.add(integer("max-pilha", "Tamanho da pilha", Material.CHEST, 1, 99, "Quantos cabem em um slot (1 a 99)."));
        f.add(choice("raridade", "Raridade", Material.AMETHYST_SHARD, "Cor do nome: common, uncommon, rare, epic.",
                "common", "uncommon", "rare", "epic"));
        f.add(map("encantamentos", "Encantamentos", Material.ENCHANTED_BOOK, "Encantamentos e níveis.", "sharpness 5", enchantments()));
        f.add(list("atributos", "Atributos", Material.GOLDEN_APPLE,
                "Bônus de atributo ao usar. Formato: atributo valor [operacao] [slot]. Operações: add_number, add_scalar, multiply_scalar_1. "
                        + "Slots: mainhand, offhand, head, chest, legs, feet, hand, armor, any.",
                "attack_damage 9 add_number mainhand", attributes()));
        f.add(bool("esconder-tooltip", "Esconder descrição", Material.BARRIER, "Esconde todo o texto ao passar o mouse."));
        f.add(list("esconder", "Esconder partes", Material.ENDER_EYE, "Esconde partes do tooltip: atributos, encantamentos, inquebravel, extra.",
                "atributos", pick("atributos", "encantamentos", "inquebravel", "extra")));
        f.add(integer("comida.nutricao", "Comida: nutrição", Material.COOKED_BEEF, 0, 20, "Define o item como comestível; fome restaurada."));
        f.add(number("comida.saturacao", "Comida: saturação", Material.GOLDEN_CARROT, 0, 20, "Saturação restaurada ao comer."));
        f.add(number("comida.tempo", "Comida: tempo de comer", Material.CLOCK, 0.05, 60, "Segundos para comer."));
        f.add(bool("comida.sempre-comer", "Comida: sempre comer", Material.CAKE, "Pode comer mesmo sem fome."));
        f.add(bool("resistente-fogo", "Resistente a fogo", Material.BLAZE_POWDER, "O item não queima em fogo ou lava."));
        f.add(integer("encantabilidade", "Encantabilidade", Material.ENCHANTING_TABLE, 1, 100, "Quão fácil é encantar na mesa."));
        f.add(bool("cabeca", "Usável na cabeça", Material.CARVED_PUMPKIN, "Pode ser equipado como capacete."));
        f.add(choice("armadura.slot", "Armadura: peça", Material.IRON_CHESTPLATE, "Torna o item uma armadura.", "head", "chest", "legs", "feet"));
        f.add(text("armadura.textura", "Armadura: textura", Material.LEATHER_CHESTPLATE,
                "Nome da textura em armor/<nome>_1.png e _2.png.", "rubi", v -> plugin.models().hasArmor(v) ? null : "textura não encontrada em armor/"));
        f.add(number("armadura.protecao", "Armadura: proteção", Material.SHIELD, 0, 30, "Pontos de armadura."));
        f.add(number("armadura.resistencia", "Armadura: resistência", Material.NETHERITE_INGOT, 0, 20, "Resistência da armadura (toughness)."));
        f.add(list("habilidades", "Habilidades", Material.BLAZE_ROD, ABILITY_HELP, ABILITY_EXAMPLE_ITEM,
                abilities(Set.of("right_click", "left_click", "sneak_right_click", "hit"))));
        f.add(grid("receita", "Receita de criação", Material.CRAFTING_TABLE, "Monta a receita em uma grade 3x3."));
        f.add(choice("receita.tipo", "Receita: tipo", Material.COMPARATOR, "shaped = posição importa; shapeless = qualquer ordem.", "shaped", "shapeless"));
        f.add(integer("receita.quantidade", "Receita: quantidade", Material.HOPPER, 1, 64, "Quantos itens a receita entrega."));
        f.add(list("cozimento", "Cozimento", Material.FURNACE, "Receitas de forno. Formato: tipo entrada [xp] [tempo] [quantidade]. Tipos: furnace, blasting, smoking, campfire.",
                "furnace IRON_ORE 0.7 200 1", cooking()));
        return f;
    }

    private static List<Field> block(CreatorCorePlugin plugin) {
        List<Field> f = new ArrayList<>();
        List<String> models = plugin.blocks().states().names();
        String shown = String.join(", ", models.stream().limit(40).toList()) + (models.size() > 40 ? "..." : "");
        f.add(text("modelo", "Modelo do bloco", Material.NOTE_BLOCK, "Visual do bloco. Disponíveis: " + shown, models.isEmpty() ? "" : models.get(0),
                v -> models.contains(v) ? null : "modelo inexistente"));
        f.add(text("nome", "Nome", Material.NAME_TAG, "Nome exibido. Aceita cores MiniMessage.", "<aqua>Bloco de Rubi", null));
        f.add(list("lore", "Descrição (lore)", Material.WRITABLE_BOOK, "Linhas de texto abaixo do nome.", "<gray>Brilha no escuro", rawLines()));
        f.add(number("dureza", "Dureza", Material.OBSIDIAN, -1, 1000, "Tempo para quebrar (pedra = 1.5, obsidiana = 50, -1 = indestrutível, 0 = instantâneo)."));
        f.add(choice("ferramenta", "Ferramenta ideal", Material.IRON_PICKAXE, "Ferramenta que acelera a quebra.", "none", "pickaxe", "axe", "shovel", "hoe"));
        f.add(choice("nivel", "Nível da ferramenta", Material.DIAMOND_PICKAXE, "Nível mínimo para dropar (com 'exigir ferramenta').",
                "wood", "stone", "iron", "diamond", "netherite"));
        f.add(bool("requer-ferramenta", "Exigir ferramenta", Material.STONE_PICKAXE, "Sem a ferramenta certa não dropa e quebra bem mais devagar."));
        f.add(list("drops", "Drops", Material.CHEST_MINECART, DROP_HELP, "cc:rubi 1-3 0.5", drops(plugin)));
        f.add(bool("fortuna", "Fortuna aumenta os drops", Material.LAPIS_LAZULI, "A Fortuna multiplica os drops."));
        f.add(integer("xp", "Experiência ao quebrar", Material.EXPERIENCE_BOTTLE, 0, 10000, "XP que cai ao quebrar."));
        f.add(bool("resiste-explosao", "Resiste a explosões", Material.TNT, "Explosões não destroem o bloco."));
        f.add(text("som-colocar", "Som ao colocar", Material.NOTE_BLOCK, "Som tocado ao colocar.", "block.stone.place", soundCheck()));
        f.add(text("som-quebrar", "Som ao quebrar", Material.JUKEBOX, "Som tocado ao quebrar.", "block.stone.break", soundCheck()));
        f.add(text("particula", "Partícula ao quebrar", Material.FIREWORK_STAR, "Partícula ao quebrar (CLOUD, FLAME, HEART, SMOKE...).", "FLAME", particleCheck()));
        f.add(integer("particula-quantidade", "Quantidade de partículas", Material.GUNPOWDER, 1, 200, "Quantas partículas."));
        f.add(list("comandos-colocar", "Comandos ao colocar", Material.COMMAND_BLOCK, COMMAND_HELP, "say %jogador% colocou", rawLines()));
        f.add(list("comandos-quebrar", "Comandos ao quebrar", Material.REPEATING_COMMAND_BLOCK, COMMAND_HELP, "give %jogador% apple", rawLines()));
        f.add(grid("receita", "Receita de criação", Material.CRAFTING_TABLE, "Monta a receita em uma grade 3x3."));
        f.add(choice("receita.tipo", "Receita: tipo", Material.COMPARATOR, "shaped = posição importa; shapeless = qualquer ordem.", "shaped", "shapeless"));
        f.add(integer("receita.quantidade", "Receita: quantidade", Material.HOPPER, 1, 64, "Quantos blocos a receita entrega."));
        f.add(list("cozimento", "Cozimento", Material.FURNACE, "Receitas de forno. Formato: tipo entrada [xp] [tempo] [quantidade].",
                "furnace COBBLESTONE 0.1 200 1", cooking()));
        return f;
    }

    private static List<Field> mob(CreatorCorePlugin plugin) {
        List<Field> f = new ArrayList<>();
        f.add(text("tipo", "Tipo base", Material.ZOMBIE_SPAWN_EGG, "Mob vanilla em que se baseia (ZOMBIE, SKELETON, WOLF, COW...).", "ZOMBIE", livingCheck()));
        f.add(text("nome", "Nome", Material.NAME_TAG, "Nome acima do mob. Aceita cores MiniMessage.", "<red>Zumbi Forte", null));
        f.add(bool("nome-visivel", "Nome sempre visível", Material.OAK_SIGN, "Mostra o nome mesmo de longe (padrão: sim)."));
        f.add(map("atributos", "Atributos", Material.GOLDEN_APPLE, "Vida, velocidade, dano, tamanho (max_health, movement_speed, attack_damage, scale, armor...).",
                "max_health 60", mobAttributes()));
        f.add(map("equipamento", "Equipamento", Material.IRON_HELMET, "Slots: mainhand, offhand, head, chest, legs, feet. Valor: material ou cc:<id>.",
                "head IRON_HELMET", equipment()));
        f.add(list("drops", "Drops", Material.CHEST_MINECART, DROP_HELP, "cc:rubi 1-2 0.3 so-jogador", drops(plugin)));
        f.add(bool("drops-vanilla", "Manter drops vanilla", Material.ROTTEN_FLESH, "Mantém também os drops normais da espécie."));
        f.add(integer("xp", "Experiência ao morrer", Material.EXPERIENCE_BOTTLE, 0, 100000, "XP dropado (vazio = o normal da espécie)."));
        f.add(bool("brilho", "Contorno brilhante", Material.GLOWSTONE_DUST, "Contorno luminoso visível através de paredes."));
        f.add(bool("silencioso", "Silencioso", Material.NOTE_BLOCK, "Sem sons."));
        f.add(bool("filhote", "Filhote", Material.EGG, "Nasce (e fica) filhote, se a espécie permitir."));
        f.add(bool("sem-ia", "Sem IA (parado)", Material.SOUL_LANTERN, "O mob não se move nem ataca (bom para estátuas e NPCs)."));
        f.add(bool("sem-gravidade", "Sem gravidade", Material.FEATHER, "Flutua no ar."));
        f.add(bool("invulneravel", "Invulnerável", Material.TOTEM_OF_UNDYING, "Não toma dano algum."));
        f.add(bool("pegar-itens", "Pode pegar itens do chão", Material.HOPPER, "Liga ou desliga a coleta de itens."));
        f.add(list("imune-a", "Imune a...", Material.NETHERITE_CHESTPLATE, "Tipos de dano ignorados: " + String.join(", ", dev.creatorcore.mob.MobExtras.immunityNames()),
                "fogo", pick(dev.creatorcore.mob.MobExtras.immunityNames().toArray(String[]::new))));
        f.add(map("efeitos", "Efeitos permanentes", Material.POTION, "Efeitos de poção que nunca acabam: efeito nível.", "speed 2", effects()));
        f.add(text("som-ferido", "Som ao ser ferido", Material.NOTE_BLOCK, "Som tocado ao levar dano.", "entity.zombie.hurt", soundCheck()));
        f.add(text("som-morte", "Som ao morrer", Material.JUKEBOX, "Som tocado ao morrer.", "entity.zombie.death", soundCheck()));
        f.add(list("comandos-morte", "Comandos ao morrer", Material.COMMAND_BLOCK, COMMAND_HELP, "say %jogador% venceu", rawLines()));
        f.add(choice("barra-chefe.cor", "Barra de chefe: cor", Material.DRAGON_HEAD, "Mostra a barra de vida no topo da tela. Escolha a cor.",
                "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE"));
        f.add(choice("barra-chefe.estilo", "Barra de chefe: estilo", Material.DRAGON_EGG, "Estilo da barra.",
                "SOLID", "SEGMENTED_6", "SEGMENTED_10", "SEGMENTED_12", "SEGMENTED_20"));
        f.add(number("barra-chefe.raio", "Barra de chefe: alcance", Material.SPYGLASS, 4, 128, "Distância em blocos em que a barra aparece."));
        f.add(bool("comportamento.passivo", "Comportamento: passivo", Material.WHEAT, "Nunca ataca ninguém."));
        f.add(text("comportamento.domavel.item", "Domável: item", Material.BONE, "Item que doma o mob com clique direito (material ou cc:<id>).", "BONE", itemCheck(plugin)));
        f.add(number("comportamento.domavel.chance", "Domável: chance", Material.RABBIT_FOOT, 0.01, 1, "Chance de 0 a 1 de domar a cada tentativa."));
        f.add(text("comportamento.distancia.projetil", "À distância: projétil", Material.BOW, "Ataca de longe: ARROW, SNOWBALL, SMALL_FIREBALL, WIND_CHARGE...", "ARROW",
                v -> projectile(v) ? null : "projétil inválido"));
        f.add(number("comportamento.distancia.velocidade", "À distância: velocidade", Material.SUGAR, 0.1, 10, "Velocidade do projétil."));
        f.add(number("comportamento.distancia.alcance", "À distância: alcance", Material.SPYGLASS, 2, 64, "Distância máxima do disparo, em blocos."));
        f.add(number("comportamento.distancia.intervalo", "À distância: intervalo", Material.CLOCK, 0.25, 600, "Segundos entre disparos."));
        f.add(number("comportamento.distancia.dano", "À distância: dano de flecha", Material.ARROW, 0, 1000, "Dano das flechas (0 = o padrão)."));
        f.add(list("comportamento.habilidades", "Habilidades do mob", Material.BLAZE_ROD, ABILITY_HELP + " Gatilhos de mob: atacar, ferido, periodica.", ABILITY_EXAMPLE_MOB,
                abilities(Set.of("atacar", "ferido", "periodica"))));
        f.add(number("spawn.chance", "Spawn natural: chance", Material.SPAWNER, 0.001, 1, "Chance de 0 a 1 de trocar o mob base por este ao nascer."));
        f.add(integer("spawn.limite", "Spawn natural: limite", Material.IRON_BARS, 1, 1000, "Máximo vivo por mundo."));
        f.add(list("spawn.mundos", "Spawn: mundos", Material.GRASS_BLOCK, "Mundos onde pode nascer (vazio = todos).", "world", rawLines()));
        f.add(list("spawn.biomas", "Spawn: biomas", Material.OAK_SAPLING, "Biomas onde pode nascer (vazio = todos).", "plains", rawLines()));
        f.add(text("modelo", "Modelo 3D", Material.ITEM_FRAME, "Modelo da pasta models/ (Blockbench/GeckoLib). Vazio = visual do mob base.", "golem_teste",
                v -> CreatorCoreIds.valid(v) ? null : "use minúsculas, números e _"));
        f.add(number("escala-modelo", "Modelo: escala", Material.SLIME_BALL, 0.05, 20, "Tamanho do modelo."));
        f.add(number("deslocamento-y", "Modelo: altura", Material.LADDER, -10, 10, "Sobe ou desce o modelo."));
        f.add(number("rotacao-modelo", "Modelo: rotação", Material.COMPASS, -360, 360, "Gira o modelo (padrão 180)."));
        for (String role : new String[] {"parado", "andando", "ataque", "morte"}) {
            f.add(text("animacoes." + role, "Animação: " + role, Material.MUSIC_DISC_CAT, "Nome da animação do modelo usada nesta situação.", role, null));
        }
        return f;
    }

    // ── Textos de ajuda ──

    private static final String DROP_HELP = "Formato: item quantidade [chance] [so-jogador]. Item = material ou cc:<id>; quantidade = 2 ou 1-3; chance 0 a 1.";
    private static final String COMMAND_HELP = "Comandos do console, sem a barra. Use %jogador%, %x%, %y%, %z%.";
    private static final String ABILITY_HELP = "Formato: gatilho recarga [consumir] | ação ; ação. "
            + "Ações: efeito <nome> <seg> <nivel> [alvo], curar <n> [alvo], dano <n>, raio, comando <texto>, projetil <TIPO> [vel], som <som> [vol], impulso <n>.";
    private static final String ABILITY_EXAMPLE_ITEM = "right_click 5 | efeito speed 10 2 ; som entity.player.levelup";
    private static final String ABILITY_EXAMPLE_MOB = "ferido 8 | efeito speed 4 2";

    // ── Verificações ──

    private static Function<String, String> materialCheck() {
        return v -> {
            Material m = Material.matchMaterial(v);
            return m != null && m.isItem() && !m.isAir() ? null : "material inexistente (ex.: DIAMOND_SWORD)";
        };
    }

    private static Function<String, String> itemCheck(CreatorCorePlugin plugin) {
        return v -> plugin.itemLoader().resolveStack(v, 1) != null ? null : "item inexistente (material ou cc:<id>)";
    }

    private static Function<String, String> soundCheck() {
        return v -> NamespacedKey.fromString(v.toLowerCase(Locale.ROOT)) == null ? "som inválido (ex.: block.stone.break)" : null;
    }

    private static Function<String, String> particleCheck() {
        return v -> {
            try {
                return org.bukkit.Particle.valueOf(v.toUpperCase(Locale.ROOT)).getDataType() == Void.class ? null
                        : "essa partícula exige dados extras; use CLOUD, FLAME, HEART...";
            } catch (IllegalArgumentException e) {
                return "partícula inexistente (ex.: FLAME)";
            }
        };
    }

    private static Function<String, String> livingCheck() {
        return v -> {
            for (EntityType t : EntityType.values()) {
                if (t.name().equalsIgnoreCase(v) && t != EntityType.UNKNOWN && t.getEntityClass() != null
                        && LivingEntity.class.isAssignableFrom(t.getEntityClass()) && t != EntityType.PLAYER
                        && t != EntityType.ARMOR_STAND && t != EntityType.GIANT) {
                    return null;
                }
            }
            return "tipo inválido (use um mob vivo, ex.: ZOMBIE)";
        };
    }

    private static boolean projectile(String name) {
        for (EntityType t : EntityType.values()) {
            if (t.name().equalsIgnoreCase(name) && t.getEntityClass() != null
                    && org.bukkit.entity.Projectile.class.isAssignableFrom(t.getEntityClass())) {
                return true;
            }
        }
        return false;
    }

    // ── Codecs ──

    private static String[] words(String input) {
        return input.strip().split("\\s+");
    }

    private static Object number(String raw, String what) {
        try {
            double d = Double.parseDouble(raw.replace(',', '.'));
            return d == Math.rint(d) && Math.abs(d) < 1e9 ? (Object) (int) d : (Object) d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " deve ser um número");
        }
    }

    private static Codec rawLines() {
        return new Codec() {
            @Override
            public Object parse(String input) {
                if (input.isBlank()) {
                    throw new IllegalArgumentException("texto vazio");
                }
                return input;
            }

            @Override
            public String show(Object entry) {
                return String.valueOf(entry);
            }
        };
    }

    private static Codec pick(String... allowed) {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String v = input.strip().toLowerCase(Locale.ROOT);
                if (!Arrays.asList(allowed).contains(v)) {
                    throw new IllegalArgumentException("opções: " + String.join(", ", allowed));
                }
                return v;
            }

            @Override
            public String show(Object entry) {
                return String.valueOf(entry);
            }
        };
    }

    /** Codec de mapa: "chave valor" -> entrada. {@code parse} devolve {@code Map.Entry}. */
    private static Codec keyed(Function<String, String> keyCheck, boolean numeric, int min, int max) {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String[] w = words(input);
                if (w.length < 2) {
                    throw new IllegalArgumentException("digite: chave valor");
                }
                String error = keyCheck.apply(w[0]);
                if (error != null) {
                    throw new IllegalArgumentException(error);
                }
                Object value = numeric ? number(w[1], "o valor") : String.join(" ", Arrays.copyOfRange(w, 1, w.length));
                if (numeric && ((Number) value).doubleValue() < min || numeric && ((Number) value).doubleValue() > max) {
                    throw new IllegalArgumentException("o valor deve estar entre " + min + " e " + max);
                }
                return Map.entry(w[0].toLowerCase(Locale.ROOT), value);
            }

            @Override
            public String show(Object entry) {
                return String.valueOf(entry);
            }
        };
    }

    private static Codec enchantments() {
        return keyed(k -> {
            NamespacedKey key = NamespacedKey.fromString(k.toLowerCase(Locale.ROOT));
            return key != null && RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) != null
                    ? null : "encantamento inexistente (ex.: sharpness, unbreaking)";
        }, true, 1, 255);
    }

    private static Codec mobAttributes() {
        return keyed(k -> {
            NamespacedKey key = NamespacedKey.fromString(k.toLowerCase(Locale.ROOT));
            return key != null && Registry.ATTRIBUTE.get(key) != null ? null : "atributo inexistente (ex.: max_health)";
        }, true, -100000, 100000);
    }

    private static Codec effects() {
        return keyed(k -> {
            NamespacedKey key = NamespacedKey.fromString(k.toLowerCase(Locale.ROOT));
            return key != null && Registry.EFFECT.get(key) != null ? null : "efeito inexistente (ex.: speed, strength)";
        }, true, 1, 255);
    }

    private static Codec equipment() {
        Set<String> slots = Set.of("mainhand", "offhand", "head", "chest", "legs", "feet");
        return keyed(k -> slots.contains(k.toLowerCase(Locale.ROOT)) ? null : "slot inválido (mainhand, offhand, head, chest, legs, feet)",
                false, 0, 0);
    }

    private static Codec attributes() {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String[] w = words(input);
                if (w.length < 2) {
                    throw new IllegalArgumentException("digite: atributo valor [operacao] [slot]");
                }
                NamespacedKey key = NamespacedKey.fromString(w[0].toLowerCase(Locale.ROOT));
                if (key == null || Registry.ATTRIBUTE.get(key) == null) {
                    throw new IllegalArgumentException("atributo inexistente (ex.: attack_damage, max_health)");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("atributo", w[0].toLowerCase(Locale.ROOT));
                m.put("valor", number(w[1], "o valor"));
                if (w.length > 2) {
                    m.put("operacao", w[2].toLowerCase(Locale.ROOT));
                }
                if (w.length > 3) {
                    m.put("slot", w[3].toLowerCase(Locale.ROOT));
                }
                return m;
            }

            @Override
            public String show(Object entry) {
                Map<?, ?> m = (Map<?, ?>) entry;
                return m.get("atributo") + " " + m.get("valor") + (m.get("slot") == null ? "" : " (" + m.get("slot") + ")");
            }
        };
    }

    private static Codec drops(CreatorCorePlugin plugin) {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String[] w = words(input);
                String item = w[0];
                if (plugin.itemLoader().resolveStack(item, 1) == null) {
                    throw new IllegalArgumentException("item '" + item + "' não existe (material ou cc:<id>)");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item", item);
                String amount = "1";
                boolean onlyPlayer = false;
                Object chance = null;
                int position = 0;
                for (int i = 1; i < w.length; i++) {
                    if (w[i].equalsIgnoreCase("so-jogador")) {
                        onlyPlayer = true;
                    } else if (position++ == 0) {
                        amount = w[i];
                    } else {
                        chance = number(w[i], "a chance");
                    }
                }
                if (!amount.matches("\\d+(-\\d+)?")) {
                    throw new IllegalArgumentException("quantidade inválida (use 2 ou 1-3)");
                }
                m.put("quantidade", amount.contains("-") ? amount : Integer.parseInt(amount));
                if (chance != null) {
                    double c = ((Number) chance).doubleValue();
                    if (c <= 0 || c > 1) {
                        throw new IllegalArgumentException("a chance deve ser de 0 a 1");
                    }
                    m.put("chance", chance);
                }
                if (onlyPlayer) {
                    m.put("so-jogador", true);
                }
                return m;
            }

            @Override
            public String show(Object entry) {
                Map<?, ?> m = (Map<?, ?>) entry;
                return m.get("item") + " x" + m.get("quantidade") + (m.get("chance") == null ? "" : " (" + m.get("chance") + ")")
                        + (Boolean.TRUE.equals(m.get("so-jogador")) ? " só jogador" : "");
            }
        };
    }

    private static Codec cooking() {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String[] w = words(input);
                if (w.length < 2) {
                    throw new IllegalArgumentException("digite: tipo entrada [xp] [tempo] [quantidade]");
                }
                if (!Set.of("furnace", "blasting", "smoking", "campfire").contains(w[0].toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("tipo: furnace, blasting, smoking ou campfire");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tipo", w[0].toLowerCase(Locale.ROOT));
                m.put("entrada", w[1]);
                if (w.length > 2) {
                    m.put("xp", number(w[2], "o xp"));
                }
                if (w.length > 3) {
                    m.put("tempo", number(w[3], "o tempo"));
                }
                if (w.length > 4) {
                    m.put("quantidade", number(w[4], "a quantidade"));
                }
                return m;
            }

            @Override
            public String show(Object entry) {
                Map<?, ?> m = (Map<?, ?>) entry;
                return m.get("tipo") + ": " + m.get("entrada");
            }
        };
    }

    private static Codec abilities(Set<String> triggers) {
        return new Codec() {
            @Override
            public Object parse(String input) {
                String[] halves = input.split("\\|", 2);
                if (halves.length < 2 || halves[1].isBlank()) {
                    throw new IllegalArgumentException("use: gatilho recarga | ação ; ação");
                }
                String[] head = words(halves[0]);
                String trigger = head[0].toLowerCase(Locale.ROOT);
                if (!triggers.contains(trigger)) {
                    throw new IllegalArgumentException("gatilho inválido (" + String.join(", ", new java.util.TreeSet<>(triggers)) + ")");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("gatilho", trigger);
                for (int i = 1; i < head.length; i++) {
                    if (head[i].equalsIgnoreCase("consumir")) {
                        m.put("consumir", true);
                    } else {
                        Object cooldown = number(head[i], "a recarga");
                        if (((Number) cooldown).doubleValue() > 0) {
                            m.put("recarga", cooldown);
                        }
                    }
                }
                List<Map<String, Object>> actions = new ArrayList<>();
                for (String segment : halves[1].split(";")) {
                    if (!segment.isBlank()) {
                        actions.add(action(segment.strip()));
                    }
                }
                if (actions.isEmpty()) {
                    throw new IllegalArgumentException("faltam as ações depois do |");
                }
                m.put("acoes", actions);
                return m;
            }

            private Map<String, Object> action(String text) {
                String[] a = words(text);
                String type = a[0].toLowerCase(Locale.ROOT);
                boolean onTarget = a.length > 1 && a[a.length - 1].equalsIgnoreCase("alvo");
                String[] args = onTarget ? Arrays.copyOf(a, a.length - 1) : a;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tipo", type);
                switch (type) {
                    case "efeito" -> {
                        if (args.length < 2) {
                            throw new IllegalArgumentException("efeito: efeito <nome> [segundos] [nivel]");
                        }
                        m.put("efeito", args[1].toLowerCase(Locale.ROOT));
                        m.put("duracao", args.length > 2 ? number(args[2], "a duração") : 10);
                        m.put("nivel", args.length > 3 ? number(args[3], "o nível") : 1);
                    }
                    case "curar", "dano", "impulso" -> {
                        if (args.length < 2) {
                            throw new IllegalArgumentException(type + ": informe o valor");
                        }
                        m.put("valor", number(args[1], "o valor"));
                    }
                    case "raio" -> { }
                    case "comando" -> {
                        if (args.length < 2) {
                            throw new IllegalArgumentException("comando: informe o comando");
                        }
                        m.put("texto", String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    }
                    case "projetil" -> {
                        if (args.length < 2 || !projectile(args[1])) {
                            throw new IllegalArgumentException("projetil: tipo inválido (SNOWBALL, ARROW, SMALL_FIREBALL...)");
                        }
                        m.put("entidade", args[1].toUpperCase(Locale.ROOT));
                        if (args.length > 2) {
                            m.put("valor", number(args[2], "a velocidade"));
                        }
                    }
                    case "som" -> {
                        if (args.length < 2) {
                            throw new IllegalArgumentException("som: informe o som (ex.: entity.player.levelup)");
                        }
                        m.put("texto", args[1].toLowerCase(Locale.ROOT));
                        if (args.length > 2) {
                            m.put("valor", number(args[2], "o volume"));
                        }
                    }
                    default -> throw new IllegalArgumentException("ação '" + type + "' inválida (efeito, curar, dano, raio, comando, projetil, som, impulso)");
                }
                if (onTarget || type.equals("dano") || type.equals("raio")) {
                    m.put("em", "alvo");
                }
                return m;
            }

            @Override
            public String show(Object entry) {
                Map<?, ?> m = (Map<?, ?>) entry;
                List<String> types = new ArrayList<>();
                if (m.get("acoes") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> am) {
                            types.add(String.valueOf(am.get("tipo")));
                        }
                    }
                }
                return m.get("gatilho") + (m.get("recarga") == null ? "" : " " + m.get("recarga") + "s") + ": " + String.join(", ", types);
            }
        };
    }

    /** Validação de ids usada por campos de texto. */
    static final class CreatorCoreIds {
        static boolean valid(String v) {
            return dev.creatorcore.registry.CreationRegistry.isValidId(v);
        }
    }
}
