# CreatorCore

Hub de criações customizadas para o Minecraft vanilla (itens, mobs, blocos e mais), para **Paper 26.3** com Java 21–25.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `src/` | Código-fonte do plugin |
| `libs/` | Um jar por versão (`CreatorCore-X.Y.Z.jar`); nunca sobrescrito |
| `scripts/` | `build.ps1` (compila) e `release.ps1` (publica no GitHub) |
| `tools/` | Geradores e utilitários (arquivos gerados não se editam à mão) |
| `resourcepack/` | Resource pack (quando houver) |
| `run/` | Servidor de teste local (fora do git) |

## Como compilar e publicar

```powershell
# 1. aumente "version=" em gradle.properties
.\scripts\build.ps1      # gera libs\CreatorCore-X.Y.Z.jar
.\scripts\release.ps1    # commit do jar, tag vX.Y.Z, push e release no GitHub
```

## Servidor de teste

`run/` (fora do git): Paper 26.3, modo offline, whitelist, portas 25600 (jogo) e 25601 (RCON). Controle por `scripts/server.ps1`:

```powershell
.\scripts\server.ps1 start            # sobe em segundo plano (usa o JDK 25 do Gradle)
.\scripts\server.ps1 rcon "list"      # comando por RCON
.\scripts\server.ps1 stop             # para por RCON
```

## Definindo itens

Em `plugins/CreatorCore/items/*.yml`, cada chave de nível superior é o id do item (minúsculas, números e `_`):

```yaml
espada_teste:
  material: DIAMOND_SWORD
  nome: "<gold>Espada de Teste"     # MiniMessage
  lore:
    - "<gray>Primeira criação."
  inquebravel: true
  brilho: true
  encantamentos:
    sharpness: 3
  atributos:                        # SUBSTITUEM os atributos do item base
    - {atributo: attack_damage, valor: 9, operacao: add_number, slot: mainhand}
  receita:
    tipo: shaped                    # ou shapeless (ingredientes: [lista])
    forma: [" P ", " P ", " S "]
    ingredientes: {P: cc:diamante_polido, S: STICK}   # material ou cc:<id>
```

O `exemplo.yml` gerado na primeira execução documenta todos os campos.

`/cc recarregar` aplica mudanças; `/cc dar <id>` entrega o item. Entradas inválidas são ignoradas com aviso no console. Os itens carregam o id em `creatorcore:id` (PDC).

## Definindo blocos

Em `plugins/CreatorCore/blocks/*.yml` (o `exemplo.yml` gerado documenta todos os campos). Cada bloco é um **estado do note block** com textura própria no resource pack; sem o pack ele aparece como note block vanilla.

```yaml
minerio_teste:
  nome: "<aqua>Minério de Teste"
  modelo: minerio_teste          # existe em src/main/resources/blocks.json e em tools/pack/build.py
  dureza: 3.0
  ferramenta: pickaxe            # none | pickaxe | axe | shovel | hoe
  nivel: iron                    # nível mínimo para dropar
  requer-ferramenta: true        # sem ela: quebra mais devagar e não dropa
  drops:
    - {item: "cc:diamante_polido", quantidade: "1-3"}
```

- **Novo bloco:** (1) adicione o modelo em `src/main/resources/blocks.json` com o próximo índice livre (nunca mude o índice de um modelo já usado em mundos), (2) desenhe a textura em `BLOCK_DRAW` de `tools/pack/build.py`, (3) crie a definição no YAML.
- **Mineração:** o servidor calcula o tempo de quebra (dureza, ferramenta, Eficiência) e mostra as rachaduras; durante a quebra o jogador recebe Fadiga de Mineração invisível, para o cliente não quebrar sozinho (o braço balança mais devagar).
- **Limitações:** ignora Pressa/Fadiga e água; pistões não movem estes blocos; som de madeira; no máximo ~800 blocos. Se algum bloco trocar de aparência perto de redstone/vizinhos, ative `block-updates.disable-noteblock-updates` no `paper-world-defaults.yml`.

## Resource pack

- **Gerador:** `scripts\build-pack.ps1` (roda `tools/pack/build.py`, requer Python 3 + Pillow). Gera `resourcepack/` e `libs/CreatorCore-Pack.zip` (zip determinístico: o SHA-1 só muda se o conteúdo mudar). **Não edite `resourcepack/` à mão**: altere `MODELS` em `tools/pack/build.py`.
- **Modelos:** cada modelo vale para um material base e é escolhido pelo campo `modelo:` do item (grava `custom_model_data` em texto). Quem não carregar o pack vê o item vanilla normal.
- **Automático:** o plugin acha o `CreatorCore-Pack.zip` na última release, baixa, calcula o SHA-1 e guarda em `pack-cache.properties` (funciona offline com o último pack). Envia ao jogador ao entrar (opcional por padrão); `/cc pacote` reenvia.
- **Manual:** `resourcepack.url` e `resourcepack.sha1` no `config.yml` substituem o automático.

## Atualização automática

O plugin consulta a última release de `KoZuuYT/CreatorCore` a cada 30 min, baixa o jar para `plugins/update/` e o servidor o aplica no próximo reinício. Configuração em `config.yml` (`updater.*`). Comandos: `/creatorcore` e `/creatorcore atualizar` (permissão `creatorcore.admin`).

## Histórico de versões

| Versão | O que trouxe |
|---|---|
| 0.1.0 | Esqueleto: classe principal, `plugin.yml`, `config.yml`, atualizador automático via GitHub, scripts de build/release |
| 0.2.0 | Servidor de teste em `run/` e `scripts/server.ps1` (start/stop/RCON); atualizador validado em servidor real |
| 0.3.0 | Base de registro de criações (`CreationRegistry`, tipos item/bloco/mob), itens definidos em YAML (`plugins/CreatorCore/items/*.yml`) e comandos `/cc listar`, `/cc recarregar`, `/cc dar <id> [jogador] [qtd]` |
| 0.4.0 | Itens com atributos, encantamentos, `inquebravel` e `brilho`; receitas (com forma e sem forma) que aceitam outros itens do plugin como ingrediente (`cc:<id>`); receitas liberadas no livro de receitas |
| 0.5.0 | Resource pack: gerador em `tools/pack`, `CreatorCore-Pack.zip` anexado à release, descoberta automática de URL/SHA-1 com cache, link manual no `config.yml`, campo `modelo:` nos itens e comando `/cc pacote` |
| 0.6.0 | Blocos customizados (estados do note block): colocar, quebrar, drops, dureza e ferramenta/nível próprios, receitas, texturas no pack; `/cc dar` e `/cc listar` cobrem blocos |
