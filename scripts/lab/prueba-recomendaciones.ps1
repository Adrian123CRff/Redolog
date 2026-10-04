param([string]$BaseUrl = 'http://127.0.0.1:8787')
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$output = Join-Path $root "runtime/pruebas-recomendaciones/$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$results = [Collections.Generic.List[object]]::new()
function Api([string]$Path, $Body = $null) {
    $args = @{Uri="$BaseUrl/api/$Path"; TimeoutSec=120}
    if ($null -ne $Body) {
        $args.Method='POST'; $args.ContentType='application/json'
        $args.Body=$Body | ConvertTo-Json -Depth 20 -Compress
    }
    Invoke-RestMethod @args
}
function Check([string]$Name, [bool]$Passed, $Evidence) {
    $results.Add([pscustomobject]@{test=$Name; passed=$Passed; evidence=$Evidence})
    Write-Host "$Name : $Passed"
    if (-not $Passed) { throw "Fallo: $Name" }
}
try {
    $state = Api 'state'
    Check 'Modo LOCAL' ($state.mode -eq 'LOCAL') $state.mode
    $db = @($state.databases | Where-Object container -eq 'rman-lab')
    Check 'Laboratorio unico' ($db.Count -eq 1) $db
    $db = $db[0]
    Check 'Sin trabajo activo' (-not $state.active.PSObject.Properties[$db.id]) $state.active
    $null = Api 'diagnose' @{databaseId=$db.id}
    $draft = @{
        name="QA_RECOMENDACION_$stamp"; description='Control preventivo sin ejecutar respaldos.'
        databaseId=$db.id; responsible='QA local'; priority='MEDIA'; enabled=$false
        scope='TABLESPACE'; tablespaces=@('FREEPDB1:LAB_DATOS'); datafiles=@()
        archivelogs=$false; controlfile=$false; spfile=$false; method='FULL'; compressed=$true; verifyAfter=$false
        startDate=(Get-Date -Format 'yyyy-MM-dd'); frequency='DIARIA'; days=@('MON','TUE','WED','THU','FRI','SAT','SUN')
        times=@(); intervalHours=$null; windowMinutes=30; destination='/opt/oracle/backup'
    }
    $strategy = (Api 'strategies' $draft).strategy
    $before = Api 'preview' @{strategy=$strategy}
    Check 'Recomendacion archived logs presente' (@($before.issues | Where-Object code -eq 'INCLUIR_ARCHIVELOGS').Count -gt 0) $before.issues
    $approval = Api 'approve' @{strategyId=$strategy.id; approvedBy='QA local'}
    $after = Api 'recommendations/apply' @{strategyId=$strategy.id; action='AGREGAR_ARCHIVELOGS'}
    $strategy = $after.strategy
    Check 'Recomendacion cambia configuracion y exige aprobacion' ($strategy.archivelogs -and -not $after.approved) $after
    $preview = Api 'preview' @{strategy=$strategy}
    Check 'Script cambia e incluye ARCHIVELOG' ($preview.hash -ne $approval.scriptHash -and $preview.script -match 'BACKUP .*ARCHIVELOG ALL') $preview
    $rejected = $false
    try { $null = Api 'run' @{strategyId=$strategy.id; operation='BACKUP'} }
    catch { $rejected = $_.ErrorDetails.Message -match 'aprueba|aprobado' }
    Check 'No ejecuta script modificado sin aprobar' $rejected $strategy.id
    $after = Api 'recommendations/apply' @{strategyId=$strategy.id; action='ACTIVAR_VERIFICACION'}
    Check 'Activa verificacion sin aprobar automaticamente' ($after.strategy.verifyAfter -and -not $after.approved) $after
    $state = Api 'state'
    $events = @($state.events | Where-Object { $_.strategyId -eq $strategy.id -and $_.type -eq 'RECOMENDACION_APLICADA' })
    Check 'Bitacora conserva ambas decisiones' ($events.Count -eq 2) $events
    $view = $state.strategies | Where-Object { $_.strategy.id -eq $strategy.id }
    Check 'Estrategia no programada ni ejecutada' (-not $view.scheduled -and -not $view.strategy.enabled -and
        @($state.executions | Where-Object strategyId -eq $strategy.id).Count -eq 0) $view
    $state | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $output 'estado-final.json') -Encoding utf8
    Write-Host "PRUEBA_RECOMENDACIONES_OK | $output"
}
finally {
    $results.ToArray() | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $output 'resultados.json') -Encoding utf8
}
