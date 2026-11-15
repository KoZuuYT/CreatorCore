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

## Atualização automática

O plugin consulta a última release de `KoZuuYT/CreatorCore` a cada 30 min, baixa o jar para `plugins/update/` e o servidor o aplica no próximo reinício. Configuração em `config.yml` (`updater.*`). Comandos: `/creatorcore` e `/creatorcore atualizar` (permissão `creatorcore.admin`).

## Histórico de versões

| Versão | O que trouxe |
|---|---|
| 0.1.0 | Esqueleto: classe principal, `plugin.yml`, `config.yml`, atualizador automático via GitHub, scripts de build/release |
