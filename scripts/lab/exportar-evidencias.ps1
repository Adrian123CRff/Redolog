param([string]$BaseUrl = 'http://127.0.0.1:8787')
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$output = Join-Path $root "runtime/entrega-evidencias/$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
function Export-Json($Value, [string]$Name) {
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $output $Name) -Encoding utf8
}
# Solo consultas HTTP: no aprueba, ejecuta ni modifica estrategias.
$state = Invoke-RestMethod "$BaseUrl/api/state"
$success = Invoke-RestMethod "$BaseUrl/api/executions/36fb774f-2022-4385-ae8e-42af8432af0d"
$failure = Invoke-RestMethod "$BaseUrl/api/executions/1c3cacb3-5db8-4eb1-84e1-5c33690cc445"
if (-not $success.execution.evidence.verified -or $failure.execution.status -ne 'FALLIDO') { throw 'La evidencia no coincide con lo esperado.' }
Export-Json $success '01-respaldo-exitoso.json'
Export-Json $failure '02-fallo-historico.json'
Export-Json $state.executions '03-historial.json'
$success.script | Set-Content -LiteralPath (Join-Path $output '04-script-ejecutado.rman') -Encoding utf8
Export-Json @($state.alerts | Where-Object code -eq 'INACTIVA' | Select-Object -First 1) '05-advertencia.json'
$recommendation = @($state.strategies | Where-Object { $_.strategy.name -eq 'QA_RECOMENDACION_20261004162948' })
if ($recommendation.Count -ne 1) { throw 'No se encontro la recomendacion de referencia.' }
$events = @($state.events | Where-Object { $_.strategyId -eq $recommendation[0].strategy.id -and $_.type -eq 'RECOMENDACION_APLICADA' })
if ($events.Count -lt 2) { throw 'Faltan eventos de recomendaciones.' }
Export-Json @{strategy=$recommendation[0]; events=$events; checks=(Get-Content -Raw -LiteralPath (Join-Path $root 'runtime/pruebas-recomendaciones/20261004162948/resultados.json') | ConvertFrom-Json)} '06-recomendacion-aplicada.json'
Export-Json @{exportedAt=[DateTimeOffset]::Now.ToString('o');mode=$state.mode;successDate=$success.execution.startedAt;failureDate=$failure.execution.startedAt;note='Paquete de evidencia historica. No certifica una nueva ejecucion del JAR corregido. El fallo RMAN es anterior; hoy el destino invalido se bloquea en validacion.'} 'manifest.json'
$files = @(Get-ChildItem -LiteralPath $output -File)
Export-Json @($files | ForEach-Object { $hash=Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256; @{file=$_.Name;sha256=$hash.Hash} }) 'sha256.json'
Write-Output "EVIDENCIAS_EXPORTADAS: $output"
