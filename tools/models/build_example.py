"""Gera src/main/resources/models/golem_teste.bbmodel, um projeto do Blockbench de exemplo
(corpo, cabeça, braços e um cristal girado 45°), com a textura embutida.

    python tools/models/build_example.py

NÃO edite o .bbmodel gerado à mão. Para modelos seus, crie o projeto no Blockbench (formato
"Modelo genérico" ou "Java Block/Item"), salve com as texturas embutidas e copie para
plugins/CreatorCore/models/<id>.bbmodel.
"""
import base64
import io
import json
import random
import uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src" / "main" / "resources" / "models" / "golem_teste.bbmodel"


def texture() -> str:
    rng = random.Random(7)
    image = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            g = rng.randint(120, 160)
            image.putpixel((x, y), (g, g - 10, g - 25, 255))
    for x in range(16):
        image.putpixel((x, 0), (70, 60, 50, 255))
        image.putpixel((x, 15), (70, 60, 50, 255))
    for y, x in [(5, 4), (5, 11)]:           # "olhos"
        image.putpixel((x, y), (90, 230, 235, 255))
        image.putpixel((x + 1, y), (90, 230, 235, 255))
    buffer = io.BytesIO()
    image.save(buffer, "PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode()


def cube(name, frm, to, rotation=None, origin=None):
    uv = [0, 0, 16, 16]
    return {
        "name": name, "type": "cube", "uuid": str(uuid.uuid5(uuid.NAMESPACE_DNS, name)),
        "from": frm, "to": to, "origin": origin or [0, 0, 0], "rotation": rotation or [0, 0, 0],
        "faces": {face: {"uv": uv, "texture": 0} for face in ("north", "east", "south", "west", "up", "down")},
    }


def main() -> None:
    elements = [
        cube("corpo", [4, 0, 5], [12, 10, 11]),
        cube("cabeca", [5, 10, 5], [11, 16, 11]),
        cube("braco_e", [0, 2, 6], [4, 10, 10]),
        cube("braco_d", [12, 2, 6], [16, 10, 10]),
        cube("cristal", [7, 17, 7], [9, 21, 9], rotation=[0, 45, 0], origin=[8, 19, 8]),
        # rotação em vários eixos (o jogo não suporta: o plugin adapta em células)
        cube("espinho", [7, 10, 11], [9, 14, 15], rotation=[30, 45, 20], origin=[8, 12, 11]),
    ]
    def gid(name):
        return str(uuid.uuid5(uuid.NAMESPACE_DNS, "grupo_" + name))

    def u(name):
        return next(e["uuid"] for e in elements if e["name"] == name)

    outliner = [{
        "name": "tronco", "uuid": gid("tronco"), "origin": [8, 10, 8], "rotation": [0, 0, 0],
        "children": [
            u("corpo"), u("espinho"),
            {"name": "cabeca", "uuid": gid("cabeca"), "origin": [8, 10, 8], "rotation": [0, 0, 0],
             "children": [u("cabeca"), u("cristal")]},
            {"name": "braco_e", "uuid": gid("braco_e"), "origin": [2, 10, 8], "rotation": [0, 0, 0],
             "children": [u("braco_e")]},
            {"name": "braco_d", "uuid": gid("braco_d"), "origin": [14, 10, 8], "rotation": [0, 0, 0],
             "children": [u("braco_d")]},
        ]}]

    def key(t, x=0, y=0, z=0, channel="rotation"):
        return {"channel": channel, "time": t, "data_points": [{"x": str(x), "y": str(y), "z": str(z)}]}

    def animator(name, *keys):
        return {"name": name, "type": "bone", "keyframes": list(keys)}

    animations = [
        {"name": "idle", "loop": "loop", "length": 2.0, "animators": {
            gid("cabeca"): animator("cabeca", key(0, 0, 0, 0), key(1, 0, 0, 4), key(2, 0, 0, 0)),
            gid("braco_d"): animator("braco_d", key(0, 0, 0, 0), key(1, 0, 0, -6), key(2, 0, 0, 0)),
            gid("braco_e"): animator("braco_e", key(0, 0, 0, 0), key(1, 0, 0, 6), key(2, 0, 0, 0))}},
        {"name": "walk", "loop": "loop", "length": 0.8, "animators": {
            gid("braco_d"): animator("braco_d", key(0, 50), key(0.4, -50), key(0.8, 50)),
            gid("braco_e"): animator("braco_e", key(0, -50), key(0.4, 50), key(0.8, -50)),
            gid("tronco"): animator("tronco", key(0, 0, 0, 3), key(0.4, 0, 0, -3), key(0.8, 0, 0, 3))}},
        {"name": "attack", "loop": "once", "length": 0.5, "animators": {
            gid("braco_d"): animator("braco_d", key(0, 0), key(0.15, -120), key(0.5, 0)),
            gid("braco_e"): animator("braco_e", key(0, 0), key(0.15, -120), key(0.5, 0))}},
        {"name": "death", "loop": "hold", "length": 1.0, "animators": {
            gid("tronco"): animator("tronco", key(0, 0), key(1.0, 90)),
            gid("tronco"): animator("tronco", key(0, 0), key(1.0, 90))}},
    ]
    project = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": "golem_teste",
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": outliner,
        "textures": [{"id": "0", "name": "golem_teste.png", "source": texture()}],
        "animations": animations,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(project), encoding="utf-8")
    print(f"OK: {OUT.relative_to(ROOT)}")


def geo_example() -> None:
    """robo_geo.geo.json + robo_geo.png: geometria Bedrock/GeckoLib (ossos, pivôs e rotações), sem animações."""
    rng = random.Random(11)
    image = Image.new("RGBA", (64, 64))
    for y in range(64):
        for x in range(64):
            g = rng.randint(150, 190)
            image.putpixel((x, y), (g - 40, g - 20, g, 255))
    for x in range(64):
        image.putpixel((x, 0), (30, 40, 70, 255))
    image.save(OUT.parent / "robo_geo.png")
    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"identifier": "geometry.robo_geo", "texture_width": 64, "texture_height": 64},
            "bones": [
                {"name": "raiz", "pivot": [0, 0, 0]},
                {"name": "corpo", "parent": "raiz", "pivot": [0, 10, 0],
                 "cubes": [{"origin": [-4, 0, -2], "size": [8, 10, 4], "uv": [16, 16]}]},
                {"name": "cabeca", "parent": "corpo", "pivot": [0, 10, 0],
                 "cubes": [{"origin": [-3, 10, -3], "size": [6, 6, 6], "uv": [0, 0]}]},
                {"name": "braco_d", "parent": "corpo", "pivot": [-5, 9, 0], "rotation": [-30, 0, 15],
                 "cubes": [{"origin": [-8, 1, -1.5], "size": [3, 9, 3], "uv": [40, 16]}]},
                {"name": "braco_e", "parent": "corpo", "pivot": [5, 9, 0], "rotation": [20, 0, -40],
                 "cubes": [{"origin": [5, 1, -1.5], "size": [3, 9, 3], "uv": [40, 16]}]},
            ],
        }],
    }
    (OUT.parent / "robo_geo.geo.json").write_text(json.dumps(geo, indent=1), encoding="utf-8")
    animations = {
        "format_version": "1.8.0",
        "animations": {
            "animation.robo_geo.idle": {"loop": True, "animation_length": 2.0, "bones": {
                "cabeca": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 15, 0], "2.0": [0, 0, 0]}},
                "braco_d": {"rotation": {"0.0": [-30, 0, 15], "1.0": [-20, 0, 15], "2.0": [-30, 0, 15]}}}},
            "animation.robo_geo.walk": {"loop": True, "animation_length": 0.8, "bones": {
                "braco_d": {"rotation": {"0.0": [45, 0, 0], "0.4": [-45, 0, 0], "0.8": [45, 0, 0]}},
                "braco_e": {"rotation": {"0.0": [-45, 0, 0], "0.4": [45, 0, 0], "0.8": [-45, 0, 0]}},
                "corpo": {"position": {"0.0": [0, 0, 0], "0.2": [0, 1, 0], "0.4": [0, 0, 0], "0.6": [0, 1, 0], "0.8": [0, 0, 0]}}}},
            "animation.robo_geo.attack": {"loop": False, "animation_length": 0.5, "bones": {
                "braco_d": {"rotation": {"0.0": [0, 0, 0], "0.15": [-110, 0, 0], "0.5": [0, 0, 0]}}}},
            "animation.robo_geo.death": {"loop": "hold_on_last_frame", "animation_length": 1.0, "bones": {
                "raiz": {"rotation": {"0.0": [0, 0, 0], "1.0": [-90, 0, 0]}}}},
        },
    }
    (OUT.parent / "robo_geo.animation.json").write_text(json.dumps(animations, indent=1), encoding="utf-8")
    print("OK: robo_geo.geo.json + robo_geo.png")


def armor_example() -> None:
    """armor/rubi_1.png (capacete, peitoral, botas) e rubi_2.png (calças): camadas humanoides 64x32."""
    out = ROOT / "src" / "main" / "resources" / "armor"
    out.mkdir(parents=True, exist_ok=True)
    for layer, base in ((1, (200, 30, 60)), (2, (160, 20, 90))):
        rng = random.Random(100 + layer)
        image = Image.new("RGBA", (64, 32))
        for y in range(32):
            for x in range(64):
                k = rng.randint(-25, 25)
                image.putpixel((x, y), (max(0, min(255, base[0] + k)), max(0, min(255, base[1] + k // 2)),
                                        max(0, min(255, base[2] + k)), 255))
        for x in range(64):             # faixas escuras para o desenho ficar visível
            for y in (7, 15, 23, 31):
                image.putpixel((x, y), (60, 10, 20, 255))
        image.save(out / f"rubi_{layer}.png")
    print("OK: armor/rubi_1.png + rubi_2.png")


if __name__ == "__main__":
    main()
    geo_example()
    armor_example()
