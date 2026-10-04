param(
    [string]$BaseUrl = 'http://127.0.0.1:8787',
    [int]$TimeoutSeconds = 1200,
    [string]$CompletedFullBackupId = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$output = Join-Path $root "runtime/pruebas-ampliadas/$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$results = [Collections.Generic.List[object]]::new()
$strategies = [Collections.Generic.List[object]]::new()
$backups = [Collections.Generic.List[object]]::new()
$qa = "QA_RMAN_$stamp"
$target = "FREEPDB1:$qa"
$container = 'rman-lab'

function SaveJson($Value, [string]$Name) {
    $Value | ConvertTo-Json -Depth 35 | Set-Content -LiteralPath (Join-Path $output "$Name.json") -Encoding utf8
}
function Check([string]$Name, [bool]$Passed, $Evidence) {
    $results.Add([pscustomobject]@{test=$Name; passed=$Passed; evidence=$Evidence})
    Write-Host "$Name : $Passed"
    if (-not $Passed) { throw "Fallo: $Name" }
}
function Api([string]$Path, $Body = $null) {
    $args = @{Uri="$BaseUrl/api/$Path"; TimeoutSec=120}
    if ($null -ne $Body) {
        $args.Method='POST'; $args.ContentType='application/json'
        $args.Body=$Body | ConvertTo-Json -Depth 20 -Compress
    }
    Invoke-RestMethod @args
}
function Sql([string]$Text, [string]$Name, [switch]$AllowError) {
    $log = "whenever sqlerror exit failure`nset pages 0 feedback off heading off lines 1000 trimspool on`n$Text`nexit`n" |
        docker exec -i -u oracle $container sqlplus -s / as sysdba 2>&1 | Out-String
    $code = $LASTEXITCODE
    $log | Set-Content -LiteralPath (Join-Path $output "$Name.log") -Encoding utf8
    if (-not $AllowError -and ($code -ne 0 -or $log -match '(?m)^\s*(ORA-|SP2-)')) { throw "SQL fallo: $Name. $log" }
    $log.Trim()
}
function Rman([string]$Text, [string]$Name) {
    $Text | Set-Content -LiteralPath (Join-Path $output "$Name.rman") -Encoding utf8
    $log = "$Text`nEXIT;`n" | docker exec -i -u oracle $container rman target / 2>&1 | Out-String
    $code = $LASTEXITCODE
    $log | Set-Content -LiteralPath (Join-Path $output "$Name.log") -Encoding utf8
    if ($code -ne 0 -or $log -match '(?m)^\s*(RMAN-00569|ORA-\d{5})') { throw "RMAN fallo: $Name. Consultar $output" }
    $log
}
function Rejected([string]$Name, [string]$Path, $Body, [string]$Pattern) {
    $message = ''
    try { $null = Api $Path $Body }
    catch { $message = $_.ErrorDetails.Message }
    Check $Name ($message -match $Pattern) $message
}
function Draft([string]$Name, [string]$Scope, [string]$Method, [bool]$Compressed = $true) {
    [ordered]@{
        name="QA_${Name}_$stamp"; description='Prueba ampliada con evidencia real; sin horarios.'
        databaseId=$db.id; responsible='QA local'; priority='MEDIA'; enabled=$false
        scope=$Scope; tablespaces=@(if ($Scope -eq 'TABLESPACE') { $target })
        datafiles=@(if ($Scope -eq 'DATAFILE') { $file })
        archivelogs=$true; controlfile=$true; spfile=$true; method=$Method
        compressed=$Compressed; verifyAfter=$true; startDate=(Get-Date -Format 'yyyy-MM-dd')
        frequency='DIARIA'; days=@('MON','TUE','WED','THU','FRI','SAT','SUN')
        times=@(); intervalHours=$null; windowMinutes=30; destination='/opt/oracle/backup'
    }
}
function Backup($Draft, [string]$Name, [switch]$Concurrency) {
    $s = (Api 'strategies' $Draft).strategy
    $strategies.Add($s)
    $preview = Api 'preview' @{strategy=$s}
    Check "$Name vista previa" (@($preview.issues | Where-Object level -eq 'ERROR').Count -eq 0) $preview.issues
    $null = Api 'approve' @{strategyId=$s.id; approvedBy='QA local'}
    $id = (Api 'run' @{strategyId=$s.id; operation='BACKUP'}).id
    if ($Concurrency) {
        Rejected 'Segundo respaldo simultaneo bloqueado' 'run' @{strategyId=$s.id; operation='BACKUP'} 'activa|cierre confirmado'
        Rejected 'Edicion durante ejecucion bloqueada' 'strategies' $s 'activa'
        Rejected 'Liberacion de trabajo en curso bloqueada' 'release' @{databaseId=$db.id} 'curso|termine'
    }
    $until = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $detail = Api "executions/$id"
        if ($detail.execution.status -ne 'EJECUTANDO') { break }
        if ((Get-Date) -ge $until) { throw "Tiempo de observacion agotado: $id. No detener RMAN ni liberar la base." }
        Start-Sleep -Seconds 2
    } while ($true)
    SaveJson $detail $Name
    Check "$Name EXITOSO" ($detail.execution.status -eq 'EXITOSO') $detail.execution
    Check "$Name verificado" ($detail.execution.evidence.verified -and $detail.execution.evidence.backupSets.Count -gt 0) $detail.execution.evidence
    $bytes = 0L
    foreach ($handle in $detail.execution.evidence.handles) {
        if (-not $handle.StartsWith('/opt/oracle/backup/')) { throw 'Pieza fuera del destino de laboratorio.' }
        $path = Join-Path $root ('runtime/backups/' + $handle.Substring('/opt/oracle/backup/'.Length))
        $bytes += (Get-Item -LiteralPath $path).Length
    }
    $backups.Add([pscustomobject]@{name=$Name; executionId=$id; bytes=$bytes; MiB=[math]::Round($bytes/1MB,2); pieces=$detail.execution.evidence.handles.Count})
    SaveJson @($backups.ToArray()) 'tamanos'
    [pscustomobject]@{strategy=$s; detail=$detail}
}
function AddRow([int]$Id, [string]$Name) {
    $null = Sql "alter session set container=FREEPDB1;`ninsert into $qa.EVENTOS values ($Id, 'MARCA_$Id', $($Id * 125));`ncommit;" $Name
}
function RecoverQa([string]$Name, [int]$ExpectedRows, [string]$Level0Tag, $IncrementalHandles) {
    # Solo se desconecta el tablespace creado en esta ejecucion. No se borra ni sobreescribe el original.
    if ($qa -notmatch '^QA_RMAN_\d{14}$' -or $file -lt 1) { throw 'Objetivo de recuperacion inseguro.' }
    $live = Sql "select count(*) from v`$datafile d join v`$tablespace t on d.ts#=t.ts# and d.con_id=t.con_id where d.file#=$file and t.name='$qa';" "$Name-guard"
    Check "$Name objetivo exclusivo QA" ([int]$live -eq 1) $target
    $newPath = "/opt/oracle/oradata/FREE/${qa}_${Name}.dbf"
    docker exec -u oracle $container test ! -e $newPath
    if ($LASTEXITCODE -ne 0) { throw "El destino ya existe: $newPath" }
    $null = Sql "alter session set container=FREEPDB1;`nalter tablespace $qa offline immediate;" "$Name-offline"
    $failure = Sql "alter session set container=FREEPDB1;`nselect count(*) from $qa.EVENTOS;" "$Name-unavailable" -AllowError
    Check "$Name consulta inaccesible antes de recuperar" ($failure -match 'ORA-00376|ORA-01110') $failure
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $log = Rman "RUN {`nSET NEWNAME FOR DATAFILE $file TO '$newPath';`nRESTORE DATAFILE $file FROM TAG '$Level0Tag';`nSWITCH DATAFILE ALL;`nRECOVER DATAFILE $file;`n}" "$Name-restore-recover"
    $null = Sql "alter session set container=FREEPDB1;`nalter tablespace $qa online;" "$Name-online"
    $watch.Stop()
    $rows = Sql "alter session set container=FREEPDB1;`nselect id||'|'||marca||'|'||importe from $qa.EVENTOS order by id;" "$Name-data"
    $expected = (1..$ExpectedRows | ForEach-Object { "$_|MARCA_$_|$($_ * 125)" }) -join "`n"
    Check "$Name datos exactos incluyendo cambio posterior" (($rows -replace "`r", '') -eq $expected) @{rows=$rows; seconds=$watch.Elapsed.TotalSeconds; restoredPath=$newPath}
    $used = @($IncrementalHandles | Where-Object { $log.Contains($_) })
    Check "$Name aplico pieza incremental" ($used.Count -gt 0) $used
    Check "$Name aplico redo" ($log -match 'archived log|media recovery complete') 'Consultar log de RECOVER.'
}

try {
    $label = docker inspect $container --format '{{index .Config.Labels "edu.respaldos.lab"}}'
    Check 'Contenedor marcado como laboratorio' ($LASTEXITCODE -eq 0 -and $label -eq 'true') $container
    $state = Api 'state'
    Check 'Aplicacion LOCAL' ($state.mode -eq 'LOCAL') $state.mode
    $dbs = @($state.databases | Where-Object container -eq $container)
    Check 'Base unica del laboratorio' ($dbs.Count -eq 1) $dbs
    $db = $dbs[0]
    Check 'Sin trabajos activos' (-not $state.active.PSObject.Properties[$db.id]) $state.active
    $scheduled = @($state.strategies | Where-Object { $_.strategy.databaseId -eq $db.id -and $_.scheduled })
    Check 'Sin horarios que interfieran' ($scheduled.Count -eq 0) $scheduled
    $diag = Api 'diagnose' @{databaseId=$db.id}
    $dataBytes = ($diag.datafiles | Measure-Object bytes -Sum).Sum
    $free = $diag.freeKb.'/opt/oracle/backup' * 1KB
    Check 'Espacio para copia completa mas 4 GiB de margen' ($free -gt $dataBytes + 4GB) @{freeBytes=$free; datafileBytes=$dataBytes}
    $originalQuery = "alter session set container=FREEPDB1;`nselect id||'|'||descripcion||'|'||importe from LAB_DEMO.PEDIDOS order by id;"
    $before = Sql $originalQuery 'pedidos-antes'

    if ($CompletedFullBackupId) {
        $full = Api "executions/$CompletedFullBackupId"
        Check 'Copia completa previa real, correcta y de esta base' ($full.execution.databaseId -eq $db.id -and
            $full.execution.status -eq 'EXITOSO' -and $full.execution.evidence.verified -and
            $full.execution.backupType -match 'base completa' -and $full.log -notmatch '\[SIMULACION\]') $full.execution
        SaveJson $full 'database-full-previo'
    } else {
        $full = Backup (Draft 'DATABASE' 'DATABASE' 'FULL') 'database-full' -Concurrency
    }

    $null = Sql "alter session set container=FREEPDB1;`ncreate tablespace $qa datafile '/opt/oracle/oradata/FREE/$qa.dbf' size 16M autoextend off;`ncreate user $qa no authentication default tablespace $qa quota 16M on $qa account lock;`ncreate table $qa.EVENTOS (id number primary key, marca varchar2(50), importe number) tablespace $qa;" 'crear-qa'
    AddRow 1 'fila-base'
    $diag = Api 'diagnose' @{databaseId=$db.id}
    $files = @($diag.datafiles | Where-Object { $_.container -eq 'FREEPDB1' -and $_.tablespace -eq $qa })
    Check 'Datafile exclusivo de QA' ($files.Count -eq 1) $files
    $file = [int]$files[0].file
    SaveJson @{tablespace=$qa; schema=$qa; file=$file; originalPath=$files[0].path} 'objetos-qa'

    $invalid = Draft 'INVALID' 'DATAFILE' 'FULL'
    $invalid.datafiles = @(65533)
    $invalidStrategy = (Api 'strategies' $invalid).strategy
    $strategies.Add($invalidStrategy)
    Rejected 'Datafile inexistente impide aprobacion' 'approve' @{strategyId=$invalidStrategy.id} 'no existe'
    Rejected 'Verificacion sin respaldo rechazada' 'run' @{strategyId=$invalidStrategy.id; operation='VALIDATE'} 'No hay un respaldo'
    Rejected 'Operacion RESTORE no expuesta por API' 'run' @{strategyId=$invalidStrategy.id; operation='RESTORE'} 'Operacion no valida'
    $invalid.destination = '/opt/oracle/backup/../oradata'
    Rejected 'Ruta con traversal rechazada' 'strategies' $invalid 'Destino no valido'

    $level0 = Backup (Draft 'LEVEL0' 'TABLESPACE' 'LEVEL0') 'tablespace-level0'
    $tag = [regex]::Match($level0.detail.script, "TAG '([^']+)'").Groups[1].Value
    if (-not $tag) { throw 'No se identifico la etiqueta del nivel 0.' }
    AddRow 2 'cambio-diferencial'
    $level1 = Backup (Draft 'LEVEL1' 'DATAFILE' 'LEVEL1' $false) 'datafile-level1-uncompressed'
    AddRow 3 'cambio-posterior-diferencial'
    $groups = [int](Sql 'select count(*) from v$log;' 'grupos-redo')
    $null = Sql ((1..($groups + 1) | ForEach-Object { 'alter system archive log current;' }) -join "`n") 'archivar-diferencial'
    $archiveDraft = Draft 'ARCHIVES' 'COMPONENTS' 'FULL'
    $archiveDraft.controlfile=$false; $archiveDraft.spfile=$false
    $archive = Backup $archiveDraft 'archivelogs-only'
    RecoverQa 'diferencial' 3 $tag $level1.detail.execution.evidence.handles

    AddRow 4 'cambio-acumulativo'
    $cumulative = Backup (Draft 'CUMULATIVE' 'DATAFILE' 'CUMULATIVE') 'datafile-cumulative'
    AddRow 5 'cambio-posterior-acumulativo'
    $null = Sql ((1..($groups + 1) | ForEach-Object { 'alter system archive log current;' }) -join "`n") 'archivar-acumulativo'
    $components = Backup (Draft 'COMPONENTS' 'COMPONENTS' 'FULL') 'components-all'
    RecoverQa 'acumulativo' 5 $tag $cumulative.detail.execution.evidence.handles

    $cf = Draft 'CONTROLFILE' 'COMPONENTS' 'FULL'
    $cf.archivelogs=$false; $cf.spfile=$false
    $null = Backup $cf 'controlfile-only'
    $sp = Draft 'SPFILE' 'COMPONENTS' 'FULL'
    $sp.archivelogs=$false; $sp.controlfile=$false
    $null = Backup $sp 'spfile-only'
    $validation = Rman 'RESTORE DATABASE VALIDATE; RESTORE CONTROLFILE VALIDATE; RESTORE SPFILE VALIDATE;' 'restore-database-validate'
    Check 'Plan de restauracion completa legible' ($validation -match 'Finished restore') 'Lectura real de las piezas, no restauracion de toda la instancia.'
    $after = Sql $originalQuery 'pedidos-despues'
    Check 'Pedidos originales sin cambios' ($before -eq $after) $after
    $status = Sql 'select open_mode from v$database;' 'estado-final'
    Check 'Base sigue READ WRITE' ($status -eq 'READ WRITE') $status
    Write-Host "PRUEBA_AMPLIADA_OK | $output"
}
finally {
    # Las estrategias nacen desactivadas y sin horarios, incluso si la prueba se interrumpe.
    SaveJson @($results.ToArray()) 'resultados'
    SaveJson @($strategies.ToArray()) 'estrategias'
    SaveJson @($backups.ToArray()) 'tamanos'
    Write-Host "Evidencia: $output"
    Write-Host "Se conservan las piezas y los objetos QA. No se borraron respaldos ni archivos originales."
}
