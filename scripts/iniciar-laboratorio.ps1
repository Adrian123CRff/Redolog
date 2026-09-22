$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$containerName = 'rman-lab'
$imageId = 'sha256:8a8084193724b95bc62247e3af4218b357e4f172c78925551b2181dbe2566232'
docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Inicia Docker Desktop antes de continuar.' }
$existing = docker ps -a --filter "name=^/$containerName$" --format '{{.Names}}'
if ($existing -eq $containerName) {
    $owner = docker inspect $containerName --format '{{index .Config.Labels "edu.respaldos.lab"}}'
    if ($owner -ne 'true') { throw 'El nombre rman-lab pertenece a otro contenedor.' }
    docker start $containerName
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar el laboratorio.' }
    exit 0
}
docker image inspect $imageId --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'No se encuentra la imagen completa de Oracle usada por este laboratorio.' }
$backupPath = Join-Path $projectRoot 'runtime\backups'
$secretPath = Join-Path $projectRoot 'runtime\oracle-password.dpapi'
New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
$securePassword = ConvertTo-SecureString ('Lab9' + [guid]::NewGuid().ToString('N')) -AsPlainText -Force
$securePassword | ConvertFrom-SecureString | Set-Content -LiteralPath $secretPath
$credential = New-Object System.Management.Automation.PSCredential('system', $securePassword)
docker volume create --label 'edu.respaldos.lab=true' rman-lab-data | Out-Null
docker run --rm --user 0 --network none --mount 'type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy' `
    --entrypoint /bin/chown $imageId oracle:oinstall /opt/oracle/oradata
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron preparar los permisos del volumen.' }
$previousPassword = $env:ORACLE_PWD
try {
    $env:ORACLE_PWD = $credential.GetNetworkCredential().Password
    docker run -d --name $containerName --label 'edu.respaldos.lab=true' --memory 3g --shm-size 1g --stop-timeout 90 `
        -p '127.0.0.1:1524:1521' -e ORACLE_PWD -e ENABLE_ARCHIVELOG=true `
        --mount 'type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy' `
        --mount "type=bind,source=$backupPath,target=/opt/oracle/backup" $imageId
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el laboratorio.' }
} finally { $env:ORACLE_PWD = $previousPassword }
Write-Output 'Laboratorio iniciandose. Puerto local: 1524. Los datos persisten en rman-lab-data.'
