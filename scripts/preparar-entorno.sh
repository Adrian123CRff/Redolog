#!/usr/bin/env bash
# Prepara todo lo necesario para ejecutar el proyecto en macOS o Linux. Se puede repetir sin riesgo.
#   ./scripts/preparar-entorno.sh             laboratorio Oracle + datos de prueba + compilacion
#   ./scripts/preparar-entorno.sh --iniciar   ademas inicia la aplicacion en http://127.0.0.1:8787
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
CONTENEDOR=rman-lab
IMAGEN="${IMAGEN:-container-registry.oracle.com/database/free:latest}"
paso() { printf '\n== %s\n' "$1"; }
falla() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

paso '1. Requisitos: Java 21+ y Docker'
command -v java >/dev/null || falla 'Instala un JDK 21 o superior (por ejemplo https://adoptium.net).'
VERSION=$(java -version 2>&1 | head -1)
MAYOR=$(printf '%s' "$VERSION" | sed -E 's/.*"([0-9]+).*/\1/')
[ "$MAYOR" -ge 21 ] 2>/dev/null || falla "Se requiere Java 21 o superior. Encontrado: $VERSION"
echo "$VERSION"
docker info >/dev/null 2>&1 || falla 'Docker no responde. Inicia Docker Desktop o el servicio de Docker.'

paso "2. Contenedor Oracle del laboratorio ($CONTENEDOR)"
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTENEDOR"; then
  [ "$(docker inspect "$CONTENEDOR" --format '{{index .Config.Labels "edu.respaldos.lab"}}')" = true ] \
    || falla "Ya existe un contenedor '$CONTENEDOR' que no pertenece a este proyecto."
  docker start "$CONTENEDOR" >/dev/null
  echo 'El contenedor ya existia; se reutiliza.'
else
  echo "Descargando $IMAGEN (unos 4 GB la primera vez)..."
  docker pull "$IMAGEN"
  mkdir -p runtime/backups
  # La clave solo la usa la imagen al crear la base; la aplicacion entra por autenticacion del sistema operativo.
  CLAVE="Lab9$(LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 32)"
  (umask 077; printf '%s\n' "$CLAVE" > runtime/oracle-password.txt)
  docker volume create --label edu.respaldos.lab=true rman-lab-data >/dev/null
  docker run --rm --user 0 --network none --mount type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy \
    --entrypoint /bin/chown "$IMAGEN" oracle:oinstall /opt/oracle/oradata
  ORACLE_PWD="$CLAVE" docker run -d --name "$CONTENEDOR" --label edu.respaldos.lab=true --memory 3g --shm-size 1g --stop-timeout 90 \
    -p 127.0.0.1:1524:1521 -e ORACLE_PWD -e ENABLE_ARCHIVELOG=true \
    --mount type=volume,source=rman-lab-data,target=/opt/oracle/oradata,volume-nocopy \
    --mount "type=bind,source=$ROOT/runtime/backups,target=/opt/oracle/backup" "$IMAGEN" >/dev/null
fi

paso '3. Esperando a que Oracle este listo (la primera vez puede tardar varios minutos)'
LIMITE=$(( $(date +%s) + 1500 ))
until [ "$(docker inspect "$CONTENEDOR" --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')" = healthy ]; do
  [ "$(date +%s)" -lt "$LIMITE" ] || falla "Oracle no quedo listo a tiempo. Revisa: docker logs $CONTENEDOR"
  printf '.'; sleep 10
done
echo ' Oracle listo.'

paso '4. Modo de archivado, datos de prueba y configuracion de RMAN'
MODO=$(printf 'set pages 0 feedback off heading off\nselect log_mode from v$database;\nexit;\n' | docker exec -i -u oracle "$CONTENEDOR" sqlplus -s / as sysdba | tr -d '[:space:]')
[ "$MODO" = ARCHIVELOG ] || echo "AVISO: la base esta en $MODO. El laboratorio espera ARCHIVELOG; la herramienta no cambia el modo."
docker exec -i -u oracle "$CONTENEDOR" sqlplus -s / as sysdba < scripts/lab/01-datos.sql >/dev/null \
  || falla 'No se pudieron crear los datos de prueba (scripts/lab/01-datos.sql).'
# Los archived logs van al volumen persistente; por defecto la imagen los deja en $ORACLE_HOME/dbs.
docker exec -u oracle "$CONTENEDOR" mkdir -p /opt/oracle/oradata/FREE/archivelog
DESTINO=$(printf "set pages 0 feedback off heading off\nselect value from v\$parameter where name = 'log_archive_dest_1';\nexit;\n" | docker exec -i -u oracle "$CONTENEDOR" sqlplus -s / as sysdba)
if ! printf '%s' "$DESTINO" | grep -q 'oradata/FREE/archivelog'; then
  printf "whenever sqlerror exit failure\nalter system set log_archive_dest_1='LOCATION=/opt/oracle/oradata/FREE/archivelog' scope=both;\nexit;\n" \
    | docker exec -i -u oracle "$CONTENEDOR" sqlplus -s / as sysdba >/dev/null || falla 'No se pudo configurar el destino de los archived logs.'
fi
printf "CONFIGURE CONTROLFILE AUTOBACKUP FORMAT FOR DEVICE TYPE DISK TO '/opt/oracle/backup/%%F';\nEXIT;\n" \
  | docker exec -i -u oracle "$CONTENEDOR" rman target / >/dev/null || falla 'No se pudo configurar el autobackup del control file.'
echo "Base en $MODO; tablespace FREEPDB1:LAB_DATOS y tabla LAB_DEMO.PEDIDOS listos."

paso '5. Compilando la aplicacion'
./mvnw -q -B package -DskipTests

paso 'Listo'
echo 'Para iniciar la aplicacion desde la carpeta del proyecto:'
echo '  java -jar target/gestor-rman-0.1.0.jar     y abre http://127.0.0.1:8787'
if [ "${1:-}" = --iniciar ]; then exec java -jar target/gestor-rman-0.1.0.jar; fi
