# Gera o resource pack (resourcepack/) e o zip libs\CreatorCore-Pack.zip.
# Requer Python 3 com Pillow. Não edite resourcepack/ à mão: altere tools\pack\build.py.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$env:PYTHONIOENCODING = 'utf-8'
python (Join-Path $root 'tools\pack\build.py')
if ($LASTEXITCODE -ne 0) { throw 'A geração do resource pack falhou.' }
