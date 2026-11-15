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

**Aplicativo:** `tools\CreatorCoreServer.exe` liga e desliga o servidor de teste (status, log, comandos por RCON, copia o jar mais novo de `libs\` para `run\plugins\`). Para recompilar: `tools\build-launcher.ps1` (usa o `csc.exe` do Windows).

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

Receitas de **cozimento** (também valem para blocos): `cozimento: [{tipo: furnace, entrada: IRON_INGOT, xp: 0.7, tempo: 200}]`. `tipo` = `furnace`, `blasting` (alto-forno), `smoking` (defumador) ou `campfire`; `entrada` = material ou `cc:<id>`; `quantidade` 1–64.

**Habilidades** (só em itens): `habilidades: [{gatilho: right_click, recarga: 1, consumir: false, acoes: [{tipo: projetil, entidade: SNOWBALL, valor: 2}]}]`. Gatilhos: `right_click`, `sneak_right_click` (com Shift), `left_click`, `hit` (bater numa entidade). Ações: `efeito`, `curar`, `dano`, `raio`, `projetil`, `impulso`, `som`, `comando` (console, `%jogador%`); `em: alvo` aplica à entidade atingida ou mirada. A recarga aparece na barra de ação.

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

## Definindo mobs

Em `plugins/CreatorCore/mobs/*.yml` (o `exemplo.yml` gerado documenta tudo). Cada mob é um mob vanilla (`tipo`) com nome, atributos, equipamento e drops próprios; o visual é o do mob base.

```yaml
zumbi_forte:
  tipo: ZOMBIE
  nome: "<red>Zumbi Forte"
  atributos: {max_health: 60, movement_speed: 0.28, attack_damage: 6}
  equipamento: {mainhand: DIAMOND_SWORD, head: IRON_HELMET}
  drops:
    - {item: "cc:diamante_polido", quantidade: "1-2"}
  xp: 30
```

**Spawn natural** (opcional): `spawn: {chance: 0.05, limite: 5, mundos: [world], biomas: [plains]}`. Quando o jogo faria nascer o mob vanilla de `tipo`, ele é trocado pelo customizado com essa chance (respeitando mundo, bioma e o limite de vivos por mundo).

`/cc invocar <id> [quantidade]` cria o mob onde você olha. Os mobs levam `creatorcore:mob` (PDC), são persistentes (não somem por distância) e substituem os drops vanilla pelos definidos (`drops-vanilla: true` mantém os dois).

## Modelos do Blockbench

Coloque projetos salvos **com as texturas embutidas** em `plugins/CreatorCore/models/<id>.bbmodel` e use `/cc recarregar`. O plugin converte para modelos do jogo, monta um pack próprio (pack da release + seus modelos) e o serve por HTTP embutido (`resourcepack.host.*` no `config.yml`; `address` deve ser um IP/domínio que os jogadores alcancem e a porta `25630` precisa estar liberada — `localhost` só serve para teste local).

- **Itens:** `modelo: <id>` no item usa o modelo importado (o `material` é só o visual de quem não carregar o pack).
- **Mobs:** `modelo: <id>` (+ `escala-modelo`, `deslocamento-y`) deixa o mob base invisível e um modelo acompanha seus movimentos (sem animações; equipamento fica oculto).
- **Suportado:** cubos, UV por face, texturas PNG embutidas, rotação de um eixo em passos de 22,5° até 45° (do cubo ou do grupo), `display`. **Não suportado** (ignorado com aviso): malhas, rotação em vários eixos ou ângulos livres, animações. Exemplo gerado: `golem_teste.bbmodel` (`tools/models/build_example.py`).

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
| 0.7.0 | Mobs customizados (`mobs/*.yml`): mob vanilla de base com nome, atributos, equipamento, drops e XP próprios; comando `/cc invocar`; app `CreatorCoreServer.exe` para ligar/desligar o servidor de teste |
| 0.8.0 | Spawn natural de mobs customizados (chance, mundo, bioma e limite por mundo) |
| 0.9.0 | Receitas de cozimento (fornalha, alto-forno, defumador, fogueira) para itens e blocos, com `cc:<id>` como entrada |
| 0.10.0 | Habilidades de itens: gatilhos (clique, Shift+clique, bater) com recarga e ações (efeito, cura, dano, raio, projétil, impulso, som, comando) |
| 0.11.0 | Importação de modelos do Blockbench (`.bbmodel`) para itens e mobs: pack próprio montado e servido pelo plugin (HTTP embutido) |
