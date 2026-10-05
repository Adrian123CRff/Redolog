# Controles del instalador con comandos sustitutos: no inicia Docker, Oracle ni Java.
$ErrorActionPreference = 'Stop'
$installer = Join-Path (Split-Path $PSScriptRoot -Parent) 'preparar-entorno.ps1'
$errors = $null
$tokens = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($installer, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
$pin = ($ast.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Imagen' }).DefaultValue.Value
if ($pin -notmatch '^container-registry.oracle.com/database/free@sha256:[0-9a-f]{64}$') { throw 'La imagen no esta fijada por digest.' }
$global:InstallerTestCalls = [System.Collections.Generic.List[string]]::new()
$global:InstallerTestPin = $pin

function java {
    $global:LASTEXITCODE = 0
    if ($args.Count -ne 1 -or $args[0] -ne '--version') { throw 'El test no debe iniciar la aplicacion.' }
    if ($global:InstallerTestCase -eq 'java-antiguo') { 'openjdk 17.0.1' } else { 'java 25.0.1' }
}

function docker {
    $command = $args -join ' '
    $global:InstallerTestCalls.Add($command)
    $global:LASTEXITCODE = 0
    switch -Wildcard ($command) {
        'info --format {{.ServerVersion}}' {
            if ($global:InstallerTestCase -eq 'docker-no-disponible') { $global:LASTEXITCODE = 1 } else { '29.0.0' }
            return
        }
        'info --format {{.OSType}}' { if ($global:InstallerTestCase -eq 'motor-windows') { 'windows' } else { 'linux' }; return }
        'ps -a *' { if ($global:InstallerTestCase -notin @('volumen-huerfano', 'instalacion-nueva')) { 'rman-lab' }; return }
        'inspect rman-lab --format *Config.Labels*' { if ($global:InstallerTestCase -eq 'contenedor-ajeno') { '{}' } else { '{"edu.respaldos.lab":"true"}' }; return }
        'inspect rman-lab --format {{.Image}}' { 'sha256:imagen-local'; return }
        'image inspect *' { if ($global:InstallerTestCase -eq 'imagen-distinta') { '["otra-imagen"]' } else { ConvertTo-Json -InputObject @($global:InstallerTestPin) -Compress }; return }
        'volume ls *' { if ($global:InstallerTestCase -eq 'volumen-huerfano') { 'rman-lab-data' }; return }
        'start rman-lab' { 'rman-lab'; return }
        'inspect rman-lab --format *State.Health*' { 'healthy'; return }
        'exec *' { throw 'TEST_STOP_AFTER_READINESS' }
        'pull *' { throw 'TEST_STOP_BEFORE_DOWNLOAD' }
        default { throw "Comando inesperado en prueba aislada: $command" }
    }
}

$cases = [ordered]@{
    'java-antiguo' = 'Se requiere Java 21'
    'docker-no-disponible' = 'Docker no responde'
    'motor-windows' = 'contenedores Linux'
    'contenedor-ajeno' = 'no pertenece a este proyecto'
    'imagen-distinta' = 'usa otra imagen'
    'volumen-huerfano' = 'sin su contenedor'
    'existente-compatible' = 'TEST_STOP_AFTER_READINESS'
    'instalacion-nueva' = 'TEST_STOP_BEFORE_DOWNLOAD'
}
foreach ($entry in $cases.GetEnumerator()) {
    $global:InstallerTestCase = $entry.Key
    $global:InstallerTestCalls.Clear()
    $message = ''
    try { & $installer } catch { $message = $_.Exception.Message }
    if ($message -notlike "*$($entry.Value)*") { throw "Caso $($entry.Key): se esperaba '$($entry.Value)', recibido '$message'." }
    if (@($global:InstallerTestCalls | Where-Object { $_ -match '^(run|rm|volume (create|rm)|system prune) ' }).Count) {
        throw 'El control preventivo permitio una operacion no esperada.'
    }
    if ($entry.Key -eq 'existente-compatible' -and -not $global:InstallerTestCalls.Contains('start rman-lab')) { throw 'No reutilizo el contenedor compatible.' }
    if ($entry.Key -eq 'instalacion-nueva' -and -not $global:InstallerTestCalls.Contains("pull $pin")) { throw 'No solicito la imagen fijada.' }
    Write-Output "OK: $($entry.Key)"
}
Write-Output 'INSTALADOR_CONTROLES_OK: 8 casos aislados; no prueban la creacion completa de Oracle.'
