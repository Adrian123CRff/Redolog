# Prueba de recuperacion en el laboratorio (enunciado 14.3 y 14.10).
# Simula la perdida de los datafiles de un tablespace, lo restaura y recupera con RMAN
# y comprueba que no se perdieron datos. Solo se ejecuta en el contenedor del laboratorio.
#   .\scripts\lab\prueba-recuperacion.ps1
param(
    [string]$Contenedor = 'rman-lab',
    [string]$Pdb = 'FREEPDB1',
    [string]$Tablespace = 'LAB_DATOS',
    [string]$Tabla = 'LAB_DEMO.PEDIDOS'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dir = Join-Path $root "runtime\pruebas-recuperacion\$stamp"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$objetivo = "${Pdb}:$Tablespace"

function Paso([string]$texto) { Write-Host "`n== $texto" -ForegroundColor Cyan }
function Sql([string]$texto, [string]$nombre, [switch]$PermitirError) {
    $salida = "whenever sqlerror exit failure`nset pages 0 feedback off heading off lines 400`n$texto`nexit`n" |
        docker exec -i -u oracle $Contenedor sqlplus -s / as sysdba 2>&1 | Out-String
    Set-Content -LiteralPath (Join-Path $dir "$nombre.log") -Value $salida
    if ($LASTEXITCODE -ne 0 -and -not $PermitirError) { throw "SQL fallo en '$nombre':`n$salida" }
    return $salida.Trim()
}
function Rman([string]$texto, [string]$nombre) {
    $salida = "$texto`nEXIT;`n" | docker exec -i -u oracle $Contenedor rman target / 2>&1 | Out-String
    Set-Content -LiteralPath (Join-Path $dir "$nombre.log") -Value $salida
    if ($LASTEXITCODE -ne 0 -or $salida -match '(?m)^\s*(RMAN-00569|ORA-\d{5})') { throw "RMAN fallo en '$nombre'. Revisa $dir\$nombre.log" }
    return $salida
}

# Solo el contenedor creado por scripts\preparar-entorno.ps1 (etiqueta edu.respaldos.lab).
$etiqueta = docker inspect $Contenedor --format '{{index .Config.Labels "edu.respaldos.lab"}}' 2>$null
if ($etiqueta -ne 'true') { throw "El contenedor $Contenedor no es el laboratorio del proyecto. No se ejecuta una prueba destructiva." }

Paso "1. Comprobar que existe un respaldo restaurable de $objetivo (sin danar nada)"
Rman "RESTORE TABLESPACE $objetivo VALIDATE;" '1-validacion-previa' | Out-Null
$archivos = @(Sql "alter session set container=$Pdb;`nselect file_name from dba_data_files where tablespace_name = '$Tablespace';" '1-datafiles' -split "`r?`n" | Where-Object { $_ -match '^/' })
if ($archivos.Count -eq 0) { throw "No se encontraron datafiles de $objetivo." }
$antes = [int](Sql "alter session set container=$Pdb;`nselect count(*) from $Tabla;" '1-filas-antes')
Write-Host "Datafiles: $($archivos -join ', ') | filas: $antes"

Paso '2. Registrar un cambio posterior al respaldo (solo se recupera aplicando los archived logs)'
$marca = "Posterior al respaldo - prueba $stamp"
Sql "alter session set container=$Pdb;`ninsert into $Tabla (id, descripcion, importe) select nvl(max(id), 0) + 1, '$marca', 1 from $Tabla;`ncommit;" '2-cambio' | Out-Null
# Tantos cambios de log como grupos de redo en linea + 1: el cambio queda solo en archived logs.
$grupos = [int](Sql 'select count(*) from v$log;' '2-grupos')
Sql ((1..($grupos + 1) | ForEach-Object { 'alter system archive log current;' }) -join "`n") '2-archivar' | Out-Null
$esperadas = $antes + 1

Paso '3. Simular la falla: tablespace offline y datafile perdido'
$inicio = Get-Date
Sql "alter session set container=$Pdb;`nalter tablespace $Tablespace offline immediate;" '3-offline' | Out-Null
foreach ($f in $archivos) { docker exec -u oracle $Contenedor mv $f "$f.danado"; if ($LASTEXITCODE -ne 0) { throw "No se pudo simular la perdida de $f" } }
$falla = Sql "alter session set container=$Pdb;`nselect count(*) from $Tabla;" '3-consulta-con-falla' -PermitirError
$errorFalla = ($falla -split "`r?`n" | Where-Object { $_ -match 'ORA-\d{5}' } | Select-Object -First 1)
Write-Host "Consulta durante la falla: $errorFalla"

try {
    Paso "4. RESTORE y RECOVER de $objetivo con RMAN"
    $rmanInicio = Get-Date
    $salida = Rman "RESTORE TABLESPACE $objetivo;`nRECOVER TABLESPACE $objetivo;" '4-restore-recover'
    $rmanSegundos = [math]::Round(((Get-Date) - $rmanInicio).TotalSeconds, 1)
    Sql "alter session set container=$Pdb;`nalter tablespace $Tablespace online;" '4-online' | Out-Null
    $fin = Get-Date
} catch {
    Write-Host "`nLa recuperacion fallo. Los archivos originales siguen como *.danado en el contenedor." -ForegroundColor Red
    Write-Host "Para volver atras: renombrarlos a su nombre original, RECOVER TABLESPACE $objetivo y ALTER TABLESPACE $Tablespace ONLINE." -ForegroundColor Red
    throw
}

Paso '5. Verificar los datos recuperados'
$despues = [int](Sql "alter session set container=$Pdb;`nselect count(*) from $Tabla;" '5-filas-despues')
$cambio = [int](Sql "alter session set container=$Pdb;`nselect count(*) from $Tabla where descripcion = '$marca';" '5-cambio-posterior')
foreach ($f in $archivos) { docker exec -u oracle $Contenedor rm -f "$f.danado" | Out-Null }

$piezas = [regex]::Matches($salida, 'piece handle=(\S+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$logs = [regex]::Matches($salida, 'archived log (?:for thread \d+ with sequence |file name=\S+ thread=\d+ sequence=)(\d+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
$ok = ($despues -eq $esperadas) -and ($cambio -eq 1)
$caida = [math]::Round(($fin - $inicio).TotalSeconds, 1)
$resumen = @"
# Prueba de recuperacion - $(Get-Date -Format 'yyyy-MM-dd HH:mm')

| Dato | Valor |
| --- | --- |
| Resultado | $(if ($ok) { 'EXITOSA: no se perdieron datos' } else { 'FALLIDA: faltan datos' }) |
| Escenario | Perdida de los datafiles de $objetivo con la base abierta |
| Datafiles | $($archivos -join ', ') |
| Error durante la falla | $errorFalla |
| Piezas restauradas | $(if ($piezas) { $piezas -join ', ' } else { '-' }) |
| Redo aplicado | $(if ($logs) { 'archived logs, secuencias ' + ($logs -join ', ') } else { 'redo logs en linea (ver 4-restore-recover.log)' }) |
| Filas antes del cambio / esperadas / recuperadas | $antes / $esperadas / $despues |
| Cambio posterior al respaldo recuperado | $(if ($cambio -eq 1) { 'Si' } else { 'No' }) |
| Tiempo de RESTORE + RECOVER | $rmanSegundos s |
| Tiempo fuera de servicio del tablespace | $caida s |

Registros completos en runtime/pruebas-recuperacion/$stamp.
"@
Set-Content -LiteralPath (Join-Path $dir 'resumen.md') -Value $resumen
Write-Host "`n$resumen"
if (-not $ok) { exit 1 }
