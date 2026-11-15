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
"""
import json
import shutil
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "resourcepack"
OUT_ZIP = ROOT / "libs" / "CreatorCore-Pack.zip"
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

    by_material: dict[str, list[str]] = {}
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
        by_material.setdefault(spec["material"], []).append(model_id)

    for material, ids in by_material.items():
        write_json(PACK / "assets" / "minecraft" / "items" / f"{material}.json", {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "cases": [
                    {"when": model_id,
                     "model": {"type": "minecraft:model", "model": f"{NAMESPACE}:item/{model_id}"}}
                    for model_id in sorted(ids)
                ],
                "fallback": {"type": "minecraft:model", "model": f"minecraft:item/{material}"},
            }
        })


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
