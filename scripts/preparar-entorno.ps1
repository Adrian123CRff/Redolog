# Prepara el laboratorio local en Windows; conserva el contenedor y los datos existentes.
#   .\scripts\preparar-entorno.ps1            laboratorio Oracle + datos de prueba + compilacion
#   .\scripts\preparar-entorno.ps1 -Iniciar   ademas inicia la aplicacion en http://127.0.0.1:8787
param(
    [switch]$Iniciar,
    [string]$Imagen = 'container-registry.oracle.com/database/free@sha256:8a8084193724b95bc62247e3af4218b357e4f172c78925551b2181dbe2566232'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$contenedor = 'rman-lab'
function Paso([string]$texto) { Write-Host "`n== $texto" -ForegroundColor Cyan }
function SqlLab([string]$texto) { $texto | docker exec -i -u oracle $contenedor sqlplus -s / as sysdba 2>&1 | Out-String }

Paso '1. Requisitos: Java 21+ y Docker'
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Instala un JDK 21 o superior (por ejemplo https://adoptium.net) y vuelve a ejecutar.' }
$javaOutput = @(& java --version)
$javaVersion = $javaOutput | Select-Object -First 1
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '^(?:openjdk|java)\s+(\d+)' -or [int]$Matches[1] -lt 21) {
    throw "Se requiere Java 21 o superior. No se pudo validar la version: $javaVersion"
}
Write-Host $javaVersion
docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker no responde. Inicia Docker Desktop y vuelve a ejecutar.' }
$motor = docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0 -or $motor -ne 'linux') { throw 'Este laboratorio requiere Docker Desktop en modo de contenedores Linux.' }

Paso "2. Contenedor Oracle del laboratorio ($contenedor)"
$existente = docker ps -a --filter "name=^/$contenedor$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron comprobar los contenedores existentes.' }
if ($existente -eq $contenedor) {
    $labelsJson = docker inspect $contenedor --format '{{json .Config.Labels}}'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar la identidad del laboratorio existente.' }
    $labels = $labelsJson | ConvertFrom-Json
    if ($labels.'edu.respaldos.lab' -ne 'true') { throw "Ya existe un contenedor '$contenedor' que no pertenece a este proyecto. Revisa el conflicto sin borrar sus datos." }
    if ($Imagen -match '@sha256:') {
        $imageId = docker inspect $contenedor --format '{{.Image}}'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo identificar la imagen del laboratorio existente.' }
        $digests = docker image inspect $imageId --format '{{json .RepoDigests}}'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar la version del laboratorio existente.' }
        $actualDigests = $digests | ConvertFrom-Json
        if ($actualDigests -notcontains $Imagen) {
            throw 'El laboratorio existente usa otra imagen. No se reemplazo ni se borraron datos. Revisar una migracion antes de cambiar de version.'
        }
    }
    docker start $contenedor | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar el laboratorio existente.' }
    Write-Host 'El contenedor ya existia; se reutiliza.'
} else {
    $volumen = docker volume ls --filter 'name=^rman-lab-data$' --format '{{.Name}}'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron comprobar los volumenes Docker.' }
    if ($volumen -eq 'rman-lab-data') {
        throw 'Existe rman-lab-data sin su contenedor. No se reutilizo ni modifico: revisar primero sus datos y como recuperar el laboratorio.'
    }
    Write-Host "Descargando $Imagen (unos 4 GB la primera vez)..."
    docker pull $Imagen
    if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar $Imagen." }
    $respaldos = Join-Path $root 'runtime\backups'
    New-Item -ItemType Directory -Path $respaldos -Force | Out-Null
    # La clave solo la usa la imagen al crear la base; la aplicacion entra por autenticacion del sistema operativo.
    $clave = ConvertTo-SecureString ('Lab9' + [guid]::NewGuid().ToString('N')) -AsPlainText -Force
    $clave | ConvertFrom-SecureString | Set-Content -LiteralPath (Join-Path $root 'runtime\oracle-password.dpapi')
    docker volume create --label 'edu.respaldos.lab=true' rman-lab-data | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el volumen del laboratorio.' }
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
Write-Host '  java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"'
Write-Host 'Y en otra terminal, el Ejecutor que corre los horarios leyendo el catalogo:'
Write-Host '  java -cp "target/gestor-rman-0.1.0.jar" edu.respaldos.Ejecutor'
Write-Host 'Abre http://127.0.0.1:8787 en este equipo.'
if ($Iniciar) {
    Write-Host 'Aplicacion en esta terminal y Ejecutor en otra ventana. Ctrl+C para detenerlos; no detiene Oracle.'
    Push-Location $root
    try {
        Start-Process powershell -WorkingDirectory $root -ArgumentList '-NoExit', '-Command', 'java -cp "target/gestor-rman-0.1.0.jar" edu.respaldos.Ejecutor'
        & java '-Djdk.net.unixdomain.tmpdir=runtime/app.lock' '-Dapp.mode=local' '-Dapp.port=8787' -jar 'target/gestor-rman-0.1.0.jar'
        if ($LASTEXITCODE -ne 0) { throw 'La aplicacion no termino correctamente. Revisa el mensaje anterior; no se elimino el catalogo.' }
    } finally {
        Pop-Location
    }
}
