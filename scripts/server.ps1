# Controla o servidor de teste em run/ (Paper, offline). Parada/comandos sempre por RCON.
# Uso:
#   .\scripts\server.ps1 start [-Dir run] [-Memory 2G]   # sobe em segundo plano e grava o PID em <Dir>\server.pid
#   .\scripts\server.ps1 rcon "<comando>" [-Dir run]     # executa um comando por RCON e mostra a resposta
#   .\scripts\server.ps1 stop [-Dir run]                 # envia "stop" por RCON
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('start', 'rcon', 'stop')][string]$Action,
    [Parameter(Position = 1)][string]$Command = '',
    [string]$Dir = 'run',
    [string]$Memory = '2G'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dirPath = if ([IO.Path]::IsPathRooted($Dir)) { $Dir } else { Join-Path $root $Dir }

function Get-Prop([string]$key) {
    $line = Get-Content (Join-Path $dirPath 'server.properties') | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1
    if (-not $line) { throw "Propriedade $key ausente em server.properties." }
    $line.Substring($key.Length + 1)
}

function Send-Rcon([string]$cmd) {
    $port = [int](Get-Prop 'rcon.port'); $pass = Get-Prop 'rcon.password'
    $client = [Net.Sockets.TcpClient]::new('127.0.0.1', $port)
    $stream = $client.GetStream()
    function Write-Packet([int]$id, [int]$type, [string]$body) {
        $b = [Text.Encoding]::UTF8.GetBytes($body)
        $ms = [IO.MemoryStream]::new(); $w = [IO.BinaryWriter]::new($ms)
        $w.Write([int](10 + $b.Length)); $w.Write($id); $w.Write($type); $w.Write($b); $w.Write([byte]0); $w.Write([byte]0)
        $bytes = $ms.ToArray(); $stream.Write($bytes, 0, $bytes.Length)
    }
    function Read-Packet {
        $hdr = [byte[]]::new(4); $n = 0
        while ($n -lt 4) { $n += $stream.Read($hdr, $n, 4 - $n) }
        $len = [BitConverter]::ToInt32($hdr, 0); $buf = [byte[]]::new($len); $n = 0
        while ($n -lt $len) { $n += $stream.Read($buf, $n, $len - $n) }
        [pscustomobject]@{ Id = [BitConverter]::ToInt32($buf, 0); Body = [Text.Encoding]::UTF8.GetString($buf, 8, $len - 10) }
    }
    try {
        Write-Packet 1 3 $pass
        if ((Read-Packet).Id -eq -1) { throw 'RCON: senha recusada.' }
        Write-Packet 2 2 $cmd
        (Read-Packet).Body
    } finally { $client.Close() }
}

switch ($Action) {
    'start' {
        $pidFile = Join-Path $dirPath 'server.pid'
        # Pasta ASCII para o socket do spark/Paper: a pasta do usuário do Windows tem acento.
        $tmp = Join-Path $env:PUBLIC 'creatorcore-java-tmp'
        New-Item -ItemType Directory -Force $tmp | Out-Null
        $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$($tmp -replace '\\', '/')"
        # O Paper 26.3 exige Java 25: usa o JDK 25 baixado pelo Gradle (cache do projeto ou do usuário).
        $java = @(
            (Join-Path $root '.gradle-home\jdks\*\bin\java.exe'),
            (Join-Path $env:USERPROFILE '.gradle\jdks\*\bin\java.exe')
        ) | ForEach-Object { Get-ChildItem $_ -ErrorAction SilentlyContinue } | Select-Object -First 1 -ExpandProperty FullName
        if (-not $java) { throw 'JDK 25 não encontrado. Rode .\scripts\build.ps1 uma vez para o Gradle baixá-lo.' }
        $p = Start-Process -FilePath $java -ArgumentList "-Xms$Memory", "-Xmx$Memory", '-jar', 'paper.jar', '--nogui' `
            -WorkingDirectory $dirPath -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $dirPath 'console.out') -RedirectStandardError (Join-Path $dirPath 'console.err')
        $p.Id | Set-Content $pidFile
        Write-Host "Servidor iniciado (PID $($p.Id)). Log: $dirPath\logs\latest.log"
    }
    'rcon' { Send-Rcon $Command }
    'stop' { Send-Rcon 'stop' }
}
