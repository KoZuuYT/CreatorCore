"""Gera o resource pack do CreatorCore (resourcepack/) e o compacta em libs/CreatorCore-Pack.zip.

    python tools/pack/build.py

NÃO edite resourcepack/ à mão: altere MODELS abaixo e rode de novo. O zip é determinístico
(timestamps fixos), então o SHA-1 só muda se o conteúdo mudar. Requer Python 3 com Pillow.

Como os itens aparecem no jogo
------------------------------
Cada modelo vale para UM material base (ex.: diamond_sword) e é escolhido pelo campo
custom_model_data (string) do item. O pack sobrescreve minecraft:items/<material>.json com um
`select` que cai no modelo vanilla quando o item não tem modelo próprio — assim, quem não carregar o
pack vê o item vanilla normal, sem texturas quebradas. No YAML do item, use `modelo: <id>` e o
mesmo `material` definido aqui.

Blocos customizados
-------------------
Cada bloco é um estado do note block, registrado em src/main/resources/blocks.json (modelo -> índice do
estado; o plugin lê o mesmo arquivo). Aqui cada modelo precisa de uma função em BLOCK_DRAW. O pack
gera o blockstate completo do note block (as combinações não reservadas continuam vanilla) e o item
do bloco (note_block + custom_model_data) cai no modelo vanilla quando não há pack.
"""
import json
import random
import shutil
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "resourcepack"
OUT_ZIP = ROOT / "libs" / "CreatorCore-Pack.zip"
BLOCKS_JSON = ROOT / "src" / "main" / "resources" / "blocks.json"
NAMESPACE = "creatorcore"

# Formato de pack aceito (mesmo intervalo usado pelo Vertexx para o Paper 26.x).
MIN_FORMAT = [97, 0]
MAX_FORMAT = [99, 0]


def draw_polished_diamond(d: ImageDraw.ImageDraw) -> None:
    d.polygon([(8, 1), (14, 6), (8, 14), (2, 6)], fill=(90, 230, 235), outline=(25, 140, 170))
    d.polygon([(8, 1), (11, 6), (5, 6)], fill=(170, 250, 255))
    d.line([(2, 6), (14, 6)], fill=(25, 140, 170))
    d.line([(8, 6), (8, 14)], fill=(40, 175, 200))
    for x, y in [(7, 3), (3, 4), (13, 4)]:
        d.point((x, y), fill=(255, 255, 255))


def draw_test_sword(d: ImageDraw.ImageDraw) -> None:
    d.line([(5, 10), (13, 2)], fill=(120, 70, 10), width=4)   # contorno da lâmina
    d.line([(5, 10), (13, 2)], fill=(255, 205, 70), width=2)  # lâmina dourada
    d.line([(4, 7), (8, 11)], fill=(110, 60, 20), width=2)    # guarda
    d.line([(5, 10), (2, 13)], fill=(90, 50, 20), width=2)    # cabo
    d.point((13, 2), fill=(255, 255, 220))
    d.point((12, 3), fill=(255, 245, 170))


def draw_test_ore(d: ImageDraw.ImageDraw, rng: random.Random) -> None:
    """Pedra cinza com veios ciano."""
    for y in range(16):
        for x in range(16):
            g = rng.randint(112, 142)
            d.point((x, y), fill=(g, g, g + 4))
    for _ in range(5):
        cx, cy = rng.randint(2, 13), rng.randint(2, 13)
        for dx, dy in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            d.point((cx + dx, cy + dy), fill=rng.choice([(90, 230, 235), (60, 200, 215), (170, 250, 255)]))


def draw_polished_block(d: ImageDraw.ImageDraw, rng: random.Random) -> None:
    """Bloco liso de diamante polido, com moldura e brilho."""
    d.rectangle([0, 0, 15, 15], fill=(90, 225, 232))
    d.rectangle([0, 0, 15, 15], outline=(30, 150, 175))
    d.rectangle([2, 2, 13, 13], outline=(140, 245, 250))
    d.line([(3, 12), (12, 3)], fill=(215, 255, 255))
    for _ in range(6):
        d.point((rng.randint(3, 12), rng.randint(3, 12)), fill=(180, 250, 255))


# Estados do note block: instrumento = estado // 50, nota = (estado % 50) // 2, powered = estado % 2.
# Tem de bater com BlockStates.java.
NOTE_INSTRUMENTS = ["harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
                    "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling"]
# Todos os instrumentos do jogo (o blockstate precisa listar todas as combinações).
ALL_NOTE_INSTRUMENTS = NOTE_INSTRUMENTS + ["zombie", "skeleton", "creeper", "dragon", "wither_skeleton",
                                          "piglin", "custom_head"]

# modelo de bloco (nome em blocks.json) -> função de desenho (16x16).
BLOCK_DRAW = {
    "minerio_teste": draw_test_ore,
    "bloco_polido": draw_polished_block,
}

# id do modelo -> material base, pai do modelo e função de desenho (16x16).
MODELS = {
    "diamante_polido": dict(material="diamond", parent="minecraft:item/generated", draw=draw_polished_diamond),
    "espada_teste": dict(material="diamond_sword", parent="minecraft:item/handheld", draw=draw_test_sword),
}


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=True) + "\n", encoding="utf-8", newline="\n")


def generate() -> None:
    if PACK.exists():
        shutil.rmtree(PACK)
    PACK.mkdir(parents=True)

    write_json(PACK / "pack.mcmeta", {
        "pack": {
            "description": "CreatorCore - itens customizados",
            "min_format": MIN_FORMAT,
            "max_format": MAX_FORMAT,
        }
    })
    icon = Image.new("RGBA", (64, 64), (24, 28, 46, 255))
    ImageDraw.Draw(icon).polygon([(32, 8), (56, 26), (32, 56), (8, 26)], fill=(90, 230, 235), outline=(25, 140, 170))
    icon.save(PACK / "pack.png")

    # material base -> lista de (valor de custom_model_data, caminho do modelo)
    by_material: dict[str, list[tuple[str, str]]] = {}
    for model_id, spec in MODELS.items():
        texture = PACK / "assets" / NAMESPACE / "textures" / "item" / f"{model_id}.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        spec["draw"](ImageDraw.Draw(image))
        image.save(texture)
        write_json(PACK / "assets" / NAMESPACE / "models" / "item" / f"{model_id}.json", {
            "parent": spec["parent"],
            "textures": {"layer0": f"{NAMESPACE}:item/{model_id}"},
        })
        by_material.setdefault(spec["material"], []).append((model_id, f"{NAMESPACE}:item/{model_id}"))

    generate_blocks(by_material)

    for material, cases in by_material.items():
        # O item de bloco (note_block) cai no modelo de bloco vanilla; os demais, no modelo de item.
        fallback = "minecraft:block/note_block" if material == "note_block" else f"minecraft:item/{material}"
        write_json(PACK / "assets" / "minecraft" / "items" / f"{material}.json", {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "cases": [
                    {"when": when, "model": {"type": "minecraft:model", "model": model}}
                    for when, model in sorted(cases)
                ],
                "fallback": {"type": "minecraft:model", "model": fallback},
            }
        })


def generate_blocks(by_material: dict) -> None:
    """Blocos customizados: estados do note block (blocks.json) -> modelos de cubo com textura própria."""
    registry = json.loads(BLOCKS_JSON.read_text(encoding="utf-8"))["modelos"]
    missing = set(registry) - set(BLOCK_DRAW)
    if missing:
        raise SystemExit(f"blocks.json tem modelos sem desenho em BLOCK_DRAW: {sorted(missing)}")
    states = {}
    for model_id, state in registry.items():
        if not 1 <= state < len(NOTE_INSTRUMENTS) * 50 or state in states:
            raise SystemExit(f"blocks.json: estado inválido ou repetido para '{model_id}' ({state})")
        states[state] = model_id

        texture = PACK / "assets" / NAMESPACE / "textures" / "block" / f"{model_id}.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
        BLOCK_DRAW[model_id](ImageDraw.Draw(image), random.Random(model_id))
        image.save(texture)
        write_json(PACK / "assets" / NAMESPACE / "models" / "block" / f"{model_id}.json", {
            "parent": "minecraft:block/cube_all",
            "textures": {"all": f"{NAMESPACE}:block/{model_id}"},
        })
        by_material.setdefault("note_block", []).append((model_id, f"{NAMESPACE}:block/{model_id}"))

    # blockstate do note block: TODAS as combinações precisam existir; as não reservadas usam o modelo vanilla.
    variants = {}
    for instrument in ALL_NOTE_INSTRUMENTS:
        for note in range(25):
            for powered in ("false", "true"):
                key = f"instrument={instrument},note={note},powered={powered}"
                variants[key] = {"model": "minecraft:block/note_block"}
    for state, model_id in states.items():
        instrument = NOTE_INSTRUMENTS[state // 50]
        key = f"instrument={instrument},note={(state % 50) // 2},powered={'true' if state % 2 else 'false'}"
        variants[key] = {"model": f"{NAMESPACE}:block/{model_id}"}
    write_json(PACK / "assets" / "minecraft" / "blockstates" / "note_block.json", {"variants": dict(sorted(variants.items()))})


def zip_pack() -> Path:
    OUT_ZIP.parent.mkdir(parents=True, exist_ok=True)
    stamp = (2020, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(p for p in PACK.rglob("*") if p.is_file()):
            info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, path.read_bytes())
    return OUT_ZIP


if __name__ == "__main__":
    generate()
    out = zip_pack()
    print(f"OK: {out.relative_to(ROOT)} ({out.stat().st_size} bytes, {len(MODELS)} modelo(s))")
