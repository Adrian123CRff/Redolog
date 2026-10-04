param(
    [string]$BaseUrl = 'http://127.0.0.1:8787',
    [int]$TimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$output = Join-Path $root "runtime/pruebas-locales/$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
$strategy = $null

function Api([string]$Path, $Body = $null) {
    $args = @{ Uri = "$BaseUrl/api/$Path"; TimeoutSec = 120 }
    if ($null -ne $Body) {
        $args.Method = 'POST'
        $args.ContentType = 'application/json'
        $args.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    Invoke-RestMethod @args
}

function Check([string]$Name, [bool]$Passed, $Evidence) {
    $results.Add([pscustomobject]@{ test=$Name; passed=$Passed; evidence=$Evidence })
    Write-Output "$Name : $Passed"
    if (-not $Passed) { throw "No se cumplio la comprobacion: $Name" }
}

function Finished([string]$Id) {
    $until = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $until) {
        $detail = Api "executions/$Id"
        if ($detail.execution.status -ne 'EJECUTANDO') { return $detail }
        Start-Sleep -Seconds 2
    }
    throw "La ejecucion $Id no termino dentro del tiempo de observacion. No se detuvo RMAN."
}

try {
    $state = Api 'state'
    Check 'Modo real' ($state.mode -eq 'LOCAL') $state.mode
    $db = @($state.databases | Where-Object container -eq 'rman-lab')
    Check 'Laboratorio unico' ($db.Count -eq 1) $db
    $db = $db[0]
    Check 'Base libre' (-not $state.active.PSObject.Properties[$db.id]) $state.active
    $diagnostic = Api 'diagnose' @{databaseId=$db.id}
    Check 'Oracle y RMAN disponibles' ($diagnostic.reachable -and $diagnostic.rmanAvailable) $diagnostic
    Check 'ARCHIVELOG' ($diagnostic.logMode -eq 'ARCHIVELOG') $diagnostic.logMode
    Check 'Tablespace de prueba' (@($diagnostic.datafiles | Where-Object { $_.container -eq 'FREEPDB1' -and $_.tablespace -eq 'LAB_DATOS' }).Count -gt 0) $diagnostic.datafiles
    Check 'Espacio para prueba pequena' ($diagnostic.freeKb.'/opt/oracle/backup' -gt 1048576) $diagnostic.freeKb

    $zone = [TimeZoneInfo]::FindSystemTimeZoneById('Central America Standard Time')
    $now = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $zone)
    $draft = [ordered]@{
        name="QA_LOCAL_$stamp"; description='Prueba no destructiva solicitada por el usuario; se desactiva al terminar.'
        databaseId=$db.id; responsible='Prueba local asistida'; priority='MEDIA'; enabled=$false
        scope='TABLESPACE'; tablespaces=@('FREEPDB1:LAB_DATOS'); datafiles=@()
        archivelogs=$true; controlfile=$true; spfile=$true; method='FULL'; compressed=$true; verifyAfter=$true
        startDate=$now.ToString('yyyy-MM-dd'); frequency='DIARIA'; days=@('MON','TUE','WED','THU','FRI','SAT','SUN')
        times=@(); intervalHours=$null; windowMinutes=5; destination='/opt/oracle/backup'
    }
    $strategy = (Api 'strategies' $draft).strategy
    $preview = Api 'preview' @{strategy=$strategy}
    Check 'Vista previa sin errores' (@($preview.issues | Where-Object level -eq 'ERROR').Count -eq 0) $preview

    $rejected = $false
    try { $null = Api 'run' @{strategyId=$strategy.id; operation='BACKUP'} }
    catch { $rejected = $_.ErrorDetails.Message -match 'aprueba|aprobado' }
    Check 'Bloqueo antes de aprobar' $rejected 'No debe despacharse RMAN sin aprobacion.'

    $validDestination = $strategy.destination
    $strategy.destination = "/opt/oracle/backup/qa_no_existe_$stamp"
    $strategy = (Api 'strategies' $strategy).strategy
    $rejected = $false
    try { $null = Api 'approve' @{strategyId=$strategy.id; approvedBy='Prueba local asistida'} }
    catch { $rejected = $_.ErrorDetails.Message -match 'destino|existe|accesible' }
    Check 'Destino inexistente impide aprobar' $rejected $strategy.destination
    $strategy.destination = $validDestination
    $strategy = (Api 'strategies' $strategy).strategy
    $null = Api 'preview' @{strategy=$strategy}
    $approval = Api 'approve' @{strategyId=$strategy.id; approvedBy='Prueba local asistida'}
    Check 'Aprobacion registrada' (-not [string]::IsNullOrWhiteSpace($approval.scriptHash)) $approval

    $manualId = (Api 'run' @{strategyId=$strategy.id; operation='BACKUP'}).id
    $manual = Finished $manualId
    $manual | ConvertTo-Json -Depth 25 | Set-Content (Join-Path $output 'respaldo-manual.json') -Encoding utf8
    Check 'Respaldo manual real exitoso' ($manual.execution.status -eq 'EXITOSO') $manual.execution
    Check 'Piezas y conjuntos identificados' ($manual.execution.pieces.Count -gt 0 -and $manual.execution.evidence.backupSets.Count -gt 0) $manual.execution.evidence
    Check 'Verificacion posterior real' ([bool]$manual.execution.evidence.verified -and $manual.verifyScript -match 'VALIDATE BACKUPSET' -and $manual.verifyLog -notmatch '\[SIMULACION\]') $manual.verifyLog

    $verificationId = (Api 'run' @{strategyId=$strategy.id; operation='VALIDATE'}).id
    $verification = Finished $verificationId
    $verification | ConvertTo-Json -Depth 25 | Set-Content (Join-Path $output 'verificacion-manual.json') -Encoding utf8
    Check 'Verificacion manual del respaldo correcto' ($verification.execution.status -eq 'EXITOSO' -and $verification.execution.evidence.verified -and $verification.execution.evidence.verifies -eq $manualId) $verification.execution

    # Margen suficiente para el diagnostico de aprobacion; no ejecutar retrospectivamente.
    $planned = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $zone).AddMinutes(2)
    $strategy.enabled = $true
    $strategy.startDate = $planned.ToString('yyyy-MM-dd')
    $strategy.times = @($planned.ToString('HH:mm'))
    $saved = Api 'strategies' $strategy
    $strategy = $saved.strategy
    Check 'Cambiar horario exige nueva aprobacion' (-not $saved.approved) $strategy.times
    $null = Api 'preview' @{strategy=$strategy}
    $null = Api 'approve' @{strategyId=$strategy.id; approvedBy='Prueba local asistida'}
    $state = Api 'state'
    $view = $state.strategies | Where-Object { $_.strategy.id -eq $strategy.id }
    # PowerShell 7 puede convertir automaticamente ISO-8601 a DateTime UTC.
    # El cast conserva Kind; Parse(DateTime.ToString()) perderia la zona original.
    $scheduledAt = [DateTimeOffset]$view.nextRun
    Check 'Proximo disparo cercano' ($view.scheduled -and $scheduledAt -gt [DateTimeOffset]::UtcNow -and $scheduledAt -lt [DateTimeOffset]::UtcNow.AddMinutes(3)) $view.nextRun
    Write-Output "Esperando disparo automatico: $($view.nextRun)"
    $until = (Get-Date).AddSeconds($TimeoutSeconds)
    $scheduled = $null
    while ((Get-Date) -lt $until) {
        $state = Api 'state'
        $scheduled = $state.executions | Where-Object { $_.strategyId -eq $strategy.id -and $_.source -eq 'HORARIO' } | Select-Object -First 1
        if ($null -ne $scheduled) { break }
        Start-Sleep -Seconds 3
    }
    Check 'Quartz disparo sin accion manual' ($null -ne $scheduled) $view.nextRun
    $scheduledDetail = Finished $scheduled.id
    $scheduledDetail | ConvertTo-Json -Depth 25 | Set-Content (Join-Path $output 'respaldo-programado.json') -Encoding utf8
    Check 'Respaldo programado real y verificado' ($scheduledDetail.execution.status -eq 'EXITOSO' -and $scheduledDetail.execution.evidence.verified) $scheduledDetail.execution
    Check 'Hora planificada registrada' ([DateTimeOffset]$scheduledDetail.execution.plannedAt -eq $scheduledAt) $scheduledDetail.execution.plannedAt
    Write-Output "PRUEBA_LOCAL_OK | Estrategia: $($strategy.id) | Evidencia: $output"
}
finally {
    if ($null -ne $strategy) {
        try {
            $strategy.enabled = $false
            $strategy.times = @()
            $null = Api 'strategies' $strategy
            Write-Output "Estrategia de prueba desactivada: $($strategy.id)"
        } catch {
            Write-Warning "No se pudo desactivar la estrategia $($strategy.id). Revisar si hay una ejecucion activa o incierta; no liberar automaticamente."
        }
    }
    $results | ConvertTo-Json -Depth 30 | Set-Content (Join-Path $output 'resultados.json') -Encoding utf8
}
