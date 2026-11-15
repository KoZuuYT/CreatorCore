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
    ]
    project = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": "golem_teste",
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": [e["uuid"] for e in elements],
        "textures": [{"id": "0", "name": "golem_teste.png", "source": texture()}],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(project), encoding="utf-8")
    print(f"OK: {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
