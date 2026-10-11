# Hoja rapida para el laboratorio de caida (lunes)

Para tener abierta durante la prueba del profesor. Regla: **primero diagnosticar,
despues restaurar**. Los pasos detallados de cada escenario estan en
[fallos-medio-fisico.md](fallos-medio-fisico.md).

Abrir dos terminales de PowerShell:

    docker exec -it -u oracle rman-lab sqlplus / as sysdba
    docker exec -it -u oracle rman-lab rman target /

## Antes de clase (15 min)

1. Docker abierto y `rman-lab` en estado healthy (`docker ps`).
2. `scripts\preparar-entorno.ps1 -Iniciar` (app + Ejecutor).
3. Respaldo fresco con el Ejecutor:

       java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA0002   # base completa nivel 0
       java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA0001   # LAB_DATOS + archived logs + control file

4. "Foto" del antes: en la app, **Bases de datos > Tomar foto del estado** (guarda el SCN
   y las filas de cada tabla; el diagnostico la usa para detectar filas o tablas perdidas).
   Para anotarla tambien a mano, en SQL*Plus:

       SELECT dbid, current_scn, TO_CHAR(SYSDATE,'YYYY-MM-DD HH24:MI:SS') FROM v$database;
       ALTER SESSION SET CONTAINER = FREEPDB1;
       SELECT COUNT(*) FROM LAB_DEMO.PEDIDOS;
       ALTER SESSION SET CONTAINER = CDB$ROOT;

5. Confirmar que los respaldos sirven, en RMAN: `RESTORE DATABASE VALIDATE;`
6. Justo antes de que el profesor empiece, en SQL*Plus: `ALTER SYSTEM ARCHIVE LOG CURRENT;`
   y en RMAN: `BACKUP ARCHIVELOG ALL NOT BACKED UP 1 TIMES;`

## Paso 1: diagnosticar (2 minutos)

En la app: **Bases de datos > Diagnosticar fallos**. Revisa todo lo de abajo (y los
archivos que faltan en disco) y propone el paso a paso con los comandos para copiar.
Solo lee: los comandos los ejecuta el DBA. Despues de cada paso que de un error nuevo,
volver a diagnosticar. Si la app no responde, hacerlo a mano en SQL*Plus, en este orden.
Lo primero que falle ya dice mucho:

    SELECT status FROM v$instance;                       -- STARTED / MOUNTED / OPEN
    SELECT name, open_mode, log_mode FROM v$database;
    SELECT name, open_mode FROM v$pdbs;
    SELECT file#, error, change# FROM v$recover_file;    -- datafiles danados
    SELECT group#, sequence#, status, archived FROM v$log;
    SELECT group#, member, status FROM v$logfile;        -- INVALID = miembro perdido

Y el final del alert log (la causa exacta suele estar ahi):

    docker exec rman-lab sh -c 'tail -60 $ORACLE_BASE/diag/rdbms/*/*/trace/alert_*.log'

## Paso 2: elegir el caso

| Lo que se ve | Caso | Que hacer |
| --- | --- | --- |
| SQL*Plus dice "Connected to an idle instance" | Instancia caida (SHUTDOWN ABORT, contenedor reiniciado) | `STARTUP;` Oracle hace la recuperacion de instancia solo. Si no abre, seguir con el error que muestre |
| `STARTUP` se queda en NOMOUNT con `ORA-00205` | Se perdieron los control files | Escenario 4: `RESTORE CONTROLFILE FROM AUTOBACKUP;` ... `OPEN RESETLOGS` |
| Queda en MOUNT con `ORA-01157` / `ORA-01110` | Falta un datafile | `v$recover_file` dice cual. Si es SYSTEM/UNDO: escenario 2. Si es de FREEPDB1: escenario 1 o 3 |
| La base abre pero una tabla da `ORA-01116` / `ORA-00376` | Datafile de usuario perdido u offline | Escenario 1 (`RESTORE/RECOVER TABLESPACE FREEPDB1:LAB_DATOS`) |
| `v$pdbs` muestra FREEPDB1 en MOUNTED | El PDB esta cerrado | `ALTER PLUGGABLE DATABASE FREEPDB1 OPEN;` Si da error de datafile: escenario 3 |
| Un miembro de `v$logfile` en INVALID | Se borro una copia del redo | Escenario 5. La base sigue gracias al espejo; recrear el miembro |
| Queda en MOUNT con `ORA-00313` / `ORA-00312` sobre el grupo CURRENT, sin espejo sano | Se perdio el redo actual | Escenario 6: recuperacion incompleta `UNTIL SEQUENCE`, `OPEN RESETLOGS` |
| Todo se congela y el alert log dice `ORA-00257` / "archiver" | Disco de archivados lleno | Liberar espacio o, en RMAN, `BACKUP ARCHIVELOG ALL DELETE INPUT;` |
| `RECOVER` pide un archived log que no existe | Se borraron archived logs | Escenario 7: recuperar hasta el ultimo disponible y despues un nivel 0 nuevo |
| No hay datafiles, control files ni SPFILE | Perdida total | Escenario 8 (DBID de la "foto") |

## Si el profesor "deshabilita" algo

| Que cambio | Como verlo | Como volver |
| --- | --- | --- |
| Tablespace offline | `SELECT tablespace_name, status FROM dba_tablespaces;` (en FREEPDB1) | `ALTER TABLESPACE LAB_DATOS ONLINE;` (si pide recover: `RECOVER TABLESPACE` en RMAN) |
| Datafile offline | `SELECT file#, status FROM v$datafile;` | RMAN: `RECOVER DATAFILE n;` y SQL: `ALTER DATABASE DATAFILE n ONLINE;` |
| Base en NOARCHIVELOG | `log_mode` en `v$database` | `SHUTDOWN IMMEDIATE; STARTUP MOUNT; ALTER DATABASE ARCHIVELOG; ALTER DATABASE OPEN;` y un nivel 0 nuevo |
| Autobackup del control file apagado | RMAN: `SHOW CONTROLFILE AUTOBACKUP;` | RMAN: `CONFIGURE CONTROLFILE AUTOBACKUP ON;` |
| Usuario bloqueado | `SELECT username, account_status FROM dba_users WHERE username='LAB_DEMO';` (en FREEPDB1) | `ALTER USER LAB_DEMO ACCOUNT UNLOCK;` |
| Tabla en modo lectura | `SELECT read_only FROM dba_tables WHERE table_name='PEDIDOS';` | `ALTER TABLE LAB_DEMO.PEDIDOS READ WRITE;` |

## Errores logicos (no son de medio, pero pueden aparecer)

Todo en SQL*Plus dentro de FREEPDB1 (`ALTER SESSION SET CONTAINER = FREEPDB1;`).

- **DROP TABLE**: la tabla queda en la papelera.

      SELECT original_name, droptime FROM dba_recyclebin WHERE owner = 'LAB_DEMO';
      FLASHBACK TABLE LAB_DEMO.PEDIDOS TO BEFORE DROP;

- **DELETE o UPDATE equivocado (ya con COMMIT)**: mientras quede undo, se puede leer
  el pasado y reinsertar.

      SELECT COUNT(*) FROM LAB_DEMO.PEDIDOS AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE);
      INSERT INTO LAB_DEMO.PEDIDOS
        SELECT * FROM LAB_DEMO.PEDIDOS AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '10' MINUTE)
        MINUS SELECT * FROM LAB_DEMO.PEDIDOS;
      COMMIT;

- **DROP TABLE ... PURGE o TRUNCATE**: no hay papelera ni undo. RMAN puede recuperar
  solo esa tabla desde los respaldos, con otro nombre, sin tocar el resto de la base
  (el diagnostico arma el comando con el SCN de la foto):

      RECOVER TABLE LAB_DEMO.PEDIDOS OF PLUGGABLE DATABASE FREEPDB1
        UNTIL SCN <scn de la foto>
        AUXILIARY DESTINATION '/opt/oracle/oradata/aux'
        REMAP TABLE LAB_DEMO.PEDIDOS:PEDIDOS_RECUPERADA;

  La alternativa es la recuperacion a un punto en el tiempo del tablespace o de la base
  (`SET UNTIL TIME`), que tambien deshace lo posterior. Explicar el costo antes de hacerlo.

## Paso 3: cerrar

1. Mostrar que el conteo de `LAB_DEMO.PEDIDOS` es igual al de la "foto" (sin perdida).
   Si hubo recuperacion incompleta, explicar hasta que secuencia u hora se llego y que se perdio.
2. Si hubo `OPEN RESETLOGS`: respaldo nuevo de inmediato (`--ahora RMA0002`).
3. Mostrar en la app el catalogo y el log de ese respaldo.
