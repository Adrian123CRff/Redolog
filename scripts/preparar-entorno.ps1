# Prepara todo lo necesario para ejecutar el proyecto en Windows. Se puede repetir sin riesgo:
# lo que ya existe se reutiliza.
#   .\scripts\preparar-entorno.ps1            laboratorio Oracle + datos de prueba + compilacion
#   .\scripts\preparar-entorno.ps1 -Iniciar   ademas inicia la aplicacion en http://127.0.0.1:8787
param(
    [switch]$Iniciar,
    [string]$Imagen = 'container-registry.oracle.com/database/free:latest'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$contenedor = 'rman-lab'
function Paso([string]$texto) { Write-Host "`n== $texto" -ForegroundColor Cyan }
function SqlLab([string]$texto) { $texto | docker exec -i -u oracle $contenedor sqlplus -s / as sysdba 2>&1 | Out-String }

Paso '1. Requisitos: Java 21+ y Docker'
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Instala un JDK 21 o superior (por ejemplo https://adoptium.net) y vuelve a ejecutar.' }
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($javaVersion -match '"(\d+)' -and [int]$Matches[1] -lt 21) { throw "Se requiere Java 21 o superior. Encontrado: $javaVersion" }
Write-Host $javaVersion
docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker no responde. Inicia Docker Desktop y vuelve a ejecutar.' }

Paso "2. Contenedor Oracle del laboratorio ($contenedor)"
$existente = docker ps -a --filter "name=^/$contenedor$" --format '{{.Names}}'
if ($existente -eq $contenedor) {
    $etiqueta = docker inspect $contenedor --format '{{index .Config.Labels "edu.respaldos.lab"}}'
    if ($etiqueta -ne 'true') { throw "Ya existe un contenedor '$contenedor' que no pertenece a este proyecto. Renombralo o eliminalo." }
    docker start $contenedor | Out-Null
    Write-Host 'El contenedor ya existia; se reutiliza.'
} else {
    Write-Host "Descargando $Imagen (unos 4 GB la primera vez)..."
    docker pull $Imagen
    if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar $Imagen." }
    $respaldos = Join-Path $root 'runtime\backups'
    New-Item -ItemType Directory -Path $respaldos -Force | Out-Null
    # La clave solo la usa la imagen al crear la base; la aplicacion entra por autenticacion del sistema operativo.
    $clave = ConvertTo-SecureString ('Lab9' + [guid]::NewGuid().ToString('N')) -AsPlainText -Force
    $clave | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $root 'runtime\oracle-password.dpapi')
    docker volume create --label 'edu.respaldos.lab=true' rman-lab-data | Out-Null
    docker run --rm --user 0 --network none --mount 'type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy' `
        --entrypoint /bin/chown $Imagen oracle:oinstall /opt/oracle/oradata
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar los permisos del volumen.' }
    $anterior = $env:ORACLE_PWD
    try {
        $env:ORACLE_PWD = [System.Net.NetworkCredential]::new('', $clave).Password
        docker run -d --name $contenedor --label 'edu.respaldos.lab=true' --memory 3g --shm-size 1g --stop-timeout 90 `
            -p '127.0.0.1:1524:1521' -e ORACLE_PWD -e ENABLE_ARCHIVELOG=true `
            --mount 'type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy' `
            --mount "type=bind,source=$respaldos,target=/opt/oracle/backup" $Imagen | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el contenedor del laboratorio.' }
    } finally { $env:ORACLE_PWD = $anterior }
}

Paso '3. Esperando a que Oracle este listo (la primera vez puede tardar varios minutos)'
$limite = (Get-Date).AddMinutes(25)
do {
    $salud = docker inspect $contenedor --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}sin-healthcheck{{end}}'
    if ($salud -eq 'healthy') { break }
    if ((Get-Date) -gt $limite) { throw "Oracle no quedo listo a tiempo (estado: $salud). Revisa: docker logs $contenedor" }
    Write-Host -NoNewline '.'; Start-Sleep -Seconds 10
} while ($true)
Write-Host 'Oracle listo.'

Paso '4. Modo de archivado, datos de prueba y configuracion de RMAN'
$modo = (SqlLab "set pages 0 feedback off heading off`nselect log_mode from v`$database;`nexit;").Trim()
if ($modo -ne 'ARCHIVELOG') { Write-Warning "La base esta en $modo. El laboratorio espera ARCHIVELOG; la herramienta no cambia el modo por si sola." }
Get-Content (Join-Path $root 'scripts\lab\01-datos.sql') -Raw | docker exec -i -u oracle $contenedor sqlplus -s / as sysdba | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron crear los datos de prueba (scripts\lab\01-datos.sql).' }
# Los archived logs van al volumen persistente; por defecto la imagen los deja en $ORACLE_HOME/dbs,
# que se pierde si se recrea el contenedor.
docker exec -u oracle $contenedor mkdir -p /opt/oracle/oradata/FREE/archivelog
$destino = (SqlLab "set pages 0 feedback off heading off`nselect value from v`$parameter where name = 'log_archive_dest_1';`nexit;").Trim()
if ($destino -notmatch 'oradata/FREE/archivelog') {
    SqlLab "whenever sqlerror exit failure`nalter system set log_archive_dest_1='LOCATION=/opt/oracle/oradata/FREE/archivelog' scope=both;`nexit;" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo configurar el destino de los archived logs.' }
}
# El autobackup del control file queda en la carpeta de respaldos y no dentro del contenedor.
"CONFIGURE CONTROLFILE AUTOBACKUP FORMAT FOR DEVICE TYPE DISK TO '/opt/oracle/backup/%F';`nEXIT;" | docker exec -i -u oracle $contenedor rman target / | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudo configurar el autobackup del control file.' }
Write-Host "Base en $modo; tablespace FREEPDB1:LAB_DATOS y tabla LAB_DEMO.PEDIDOS listos."

Paso '5. Compilando la aplicacion'
Push-Location $root
try {
    & (Join-Path $root 'mvnw.cmd') -q -B package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'La compilacion fallo.' }
} finally { Pop-Location }

Paso 'Listo'
Write-Host 'Para iniciar la aplicacion desde la carpeta del proyecto:'
Write-Host '  java -jar target\gestor-rman-0.1.0.jar     y abre http://127.0.0.1:8787'
if ($Iniciar) {
    $app = Start-Process java -ArgumentList '-jar', 'target\gestor-rman-0.1.0.jar' -WorkingDirectory $root -NoNewWindow -PassThru
    $limite = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $limite -and -not $app.HasExited) {
        try { Invoke-WebRequest 'http://127.0.0.1:8787/api/state' -UseBasicParsing -TimeoutSec 2 | Out-Null; break } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $app.HasExited) { Start-Process 'http://127.0.0.1:8787'; Write-Host 'Aplicacion en ejecucion. Ctrl+C para detenerla.'; Wait-Process -Id $app.Id }
}
