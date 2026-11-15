# Publica a versão atual (gradle.properties) como release no GitHub.
# Servidores com o CreatorCore instalado baixam essa release sozinhos.
#
# Passo a passo de uma nova versão:
#   1. Altere "version=" em gradle.properties (antes de compilar!)
#   2. Atualize o README.md e faça o commit das mudanças
#   3. .\scripts\release.ps1
#
# O log fica FORA do repositório (%TEMP%), senão a árvore fica suja e o script aborta.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $env:TEMP 'creatorcore-release.log'
Start-Transcript -Path $log -Force | Out-Null

try {
    & (Join-Path $PSScriptRoot 'build.ps1')
    & (Join-Path $PSScriptRoot 'build-pack.ps1')

    $version = (Select-String -Path (Join-Path $root 'gradle.properties') -Pattern '^version=(.+)$').Matches[0].Groups[1].Value
    $tag = "v$version"
    $jar = Join-Path $root "libs\CreatorCore-$version.jar"
    $pack = Join-Path $root 'libs\CreatorCore-Pack.zip'
    if (-not (Test-Path $jar)) { throw "Jar não encontrado: $jar" }
    if (-not (Test-Path $pack)) { throw "Resource pack não encontrado: $pack" }

    Push-Location $root
    try {
        git add "libs/CreatorCore-$version.jar" libs/CreatorCore-Pack.zip resourcepack
        git diff --cached --quiet
        if ($LASTEXITCODE -ne 0) { git commit -m "Exporta CreatorCore-$version.jar e o resource pack`n`nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>" }

        if (git status --porcelain) { throw 'Há mudanças sem commit. Faça o commit antes de publicar.' }

        if (-not (git tag --list $tag)) { git tag $tag }
        git push origin HEAD --tags
        if ($LASTEXITCODE -ne 0) { throw 'git push falhou.' }

        gh release create $tag $jar $pack --title "CreatorCore $version" --generate-notes --latest
        if ($LASTEXITCODE -ne 0) { throw 'gh release create falhou.' }
    } finally {
        Pop-Location
    }
    Write-Host "Release $tag publicada." -ForegroundColor Green
} finally {
    Stop-Transcript | Out-Null
}
