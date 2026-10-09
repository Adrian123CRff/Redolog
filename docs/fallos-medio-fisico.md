# Fallos de medio fisico: riesgo, estrategia y recuperacion

Investigacion pedida en la clase del 05/10/2026 para el laboratorio de caida de la
base. Cubre solo fallos de **medio fisico** (se pierde o se dana un archivo de la
base), no fallos logicos (un DELETE equivocado, un DROP TABLE).

Supuestos del laboratorio: Oracle Free en Docker (`rman-lab`), contenedor CDB con
el PDB `FREEPDB1`, base en **ARCHIVELOG**, autobackup del control file activado y
respaldos en `/opt/oracle/backup`. RMAN es *Recovery* Manager: las estrategias se
disenan para poder **restaurar y recuperar**, no solo para copiar.

## Primeros 5 minutos: diagnosticar que se perdio

Antes de restaurar nada hay que saber que archivo fallo y en que estado esta la base.

| Pregunta | Donde mirarlo |
| --- | --- |
| Que error dio Oracle | Alert log: `docker exec rman-lab sh -c 'tail -100 $ORACLE_BASE/diag/rdbms/*/*/trace/alert_*.log'` |
| En que estado esta la instancia | `SELECT status FROM v$instance;` y `SELECT name, open_mode FROM v$database;` |
| Que datafiles necesitan recuperacion | `SELECT file#, error, change# FROM v$recover_file;` |
| A que tablespace pertenece cada archivo | `SELECT file#, name, status FROM v$datafile;` y `v$datafile_header` |
| Estado de los redo en linea | `SELECT group#, sequence#, status, archived FROM v$log;` y `SELECT group#, member, status FROM v$logfile;` |
| Que respaldos tengo | RMAN: `LIST BACKUP SUMMARY;` `LIST BACKUP OF TABLESPACE FREEPDB1:LAB_DATOS;` `LIST BACKUP OF CONTROLFILE;` |
| Si los respaldos siguen en disco | RMAN: `CROSSCHECK BACKUP;` `RESTORE ... VALIDATE;` (lee sin restaurar) |

En el gestor, la vista **Catalogo** muestra que estrategia respalda cada elemento,
su ultima ejecucion, las piezas y el log.

## Tabla de escenarios

Riesgo: impacto si ocurre y no hay estrategia. **Critico** = la base se detiene;
**Alto** = una parte de los datos queda inaccesible; **Medio** = la base sigue,
pero queda expuesta; **Bajo** = se corrige sin perder datos ni servicio.

| # | Escenario | Que significa | Riesgo | Estrategia que lo cubre | Recuperacion (resumen) |
| --- | --- | --- | --- | --- | --- |
| 1 | Perdida de un datafile de un tablespace de usuario | Se borra o dana el archivo de `LAB_DATOS` (o `USERS`). La base sigue abierta; las consultas a esas tablas dan `ORA-01116` / `ORA-01110` | Alto | Tablespaces con nivel 0 + nivel 1, **archived logs** y control file | Tablespace OFFLINE, `RESTORE TABLESPACE`, `RECOVER TABLESPACE`, ONLINE. Sin perdida de datos |
| 2 | Perdida de un datafile de SYSTEM o UNDO | Se pierde un archivo del diccionario o del undo del contenedor raiz. La instancia se cae o no abre | Critico | **Full** (base completa) nivel 0 semanal + nivel 1 + archived logs | `STARTUP MOUNT`, `RESTORE DATAFILE n`, `RECOVER DATAFILE n`, `ALTER DATABASE OPEN`. Sin perdida de datos |
| 3 | Perdida de todos los datafiles de un PDB | Se pierde el disco o la carpeta de `FREEPDB1`. El CDB sigue, el PDB no abre | Alto | Full o todos los tablespaces del PDB + archived logs | `ALTER PLUGGABLE DATABASE ... CLOSE ABORT`, `RESTORE PLUGGABLE DATABASE`, `RECOVER PLUGGABLE DATABASE`, `OPEN` |
| 4 | Perdida de todos los control files | Sin control file la instancia se cae y no puede montar | Critico | **Control file** en cada estrategia + autobackup + `BACKUP CONTROLFILE TO TRACE` como respaldo documental | `STARTUP NOMOUNT`, `RESTORE CONTROLFILE FROM AUTOBACKUP`, `MOUNT`, `RECOVER DATABASE`, `OPEN RESETLOGS` |
| 5 | Perdida de un miembro o de un grupo de redo en linea INACTIVO | Si el redo esta multiplexado, el otro miembro sigue trabajando. Si se pierde un grupo que ya se archivo, no se pierden datos | Medio (Bajo si esta multiplexado) | Multiplexar los redo (dos miembros por grupo, en discos distintos) | `DROP LOGFILE MEMBER` y `ADD LOGFILE MEMBER`, o `CLEAR LOGFILE GROUP n`. No hace falta RMAN |
| 6 | Perdida del grupo de redo CURRENT (o ACTIVE) | Se pierde el redo que todavia no se archivo. Lo que estaba solo en ese grupo se pierde | Critico | Archived logs frecuentes + **redo en linea** (archivar el actual) para achicar la ventana de perdida | Recuperacion **incompleta**: `RESTORE DATABASE`, `RECOVER DATABASE UNTIL SEQUENCE` (o `UNTIL CANCEL`), `OPEN RESETLOGS` |
| 7 | Perdida de archived logs no respaldados | Se borra la carpeta de archivados antes de copiarlos. La cadena de recuperacion se corta: no se puede recuperar mas alla del hueco | Alto | Estrategia de **archived logs cada pocas horas** (`NOT BACKED UP 1 TIMES`) | `CROSSCHECK ARCHIVELOG ALL`; hacer **de inmediato un nivel 0 o Full** nuevo para empezar una cadena sana |
| 8 | Perdida total (datafiles, control files, SPFILE) | Se pierde el disco completo de la base. Solo quedan los respaldos | Critico | Full + archived logs + control file + SPFILE, con los respaldos **en otro disco** | `SET DBID`, `RESTORE SPFILE FROM AUTOBACKUP`, `RESTORE CONTROLFILE FROM AUTOBACKUP`, `RESTORE DATABASE`, `RECOVER DATABASE`, `OPEN RESETLOGS` |

Otros casos de medio que conviene tener a mano:

| Escenario | Riesgo | Que hacer |
| --- | --- | --- |
| Bloques corruptos en un datafile | Medio | `VALIDATE DATAFILE n;` y `SELECT * FROM v$database_block_corruption;`, despues `RECOVER CORRUPTION LIST;` (repara solo los bloques) |
| Perdida del SPFILE con la base abierta | Medio | `CREATE SPFILE FROM MEMORY;` o, con la base caida, `RESTORE SPFILE FROM AUTOBACKUP;` |
| Perdida de un tempfile | Bajo | No se respalda: `ALTER TABLESPACE TEMP ADD TEMPFILE ... SIZE 100M;` y borrar el perdido |

## Instrucciones por escenario

Los comandos SQL se ejecutan con `docker exec -it -u oracle rman-lab sqlplus / as sysdba`
y los de RMAN con `docker exec -it -u oracle rman-lab rman target /`.

### 1. Datafile de un tablespace de usuario (base abierta)

Es el caso que el proyecto ya probo de punta a punta (`scripts/lab/prueba-recuperacion.ps1`,
evidencia en `docs/evidencias/prueba-recuperacion.md`).

```sql
-- SQL*Plus: ver que esta danado
SELECT file#, error FROM v$recover_file;
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER TABLESPACE LAB_DATOS OFFLINE IMMEDIATE;
```
```rman
# RMAN (contenedor raiz)
RESTORE TABLESPACE FREEPDB1:LAB_DATOS;
RECOVER TABLESPACE FREEPDB1:LAB_DATOS;
```
```sql
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER TABLESPACE LAB_DATOS ONLINE;
```

`RECOVER` aplica el nivel 1 y los archived logs: se recupera hasta el ultimo commit.

### 2. Datafile de SYSTEM o UNDO

```sql
SHUTDOWN ABORT;
STARTUP MOUNT;
SELECT file#, error FROM v$recover_file;   -- por ejemplo file# 1 (SYSTEM)
```
```rman
RESTORE DATAFILE 1;
RECOVER DATAFILE 1;
ALTER DATABASE OPEN;
```

### 3. Un PDB completo

```rman
ALTER PLUGGABLE DATABASE FREEPDB1 CLOSE ABORT;
RESTORE PLUGGABLE DATABASE FREEPDB1;
RECOVER PLUGGABLE DATABASE FREEPDB1;
ALTER PLUGGABLE DATABASE FREEPDB1 OPEN;
```

### 4. Todos los control files

Si solo se perdio **una** copia y el control file esta multiplexado, basta con apagar,
copiar una copia sana sobre la danada y arrancar. Si se perdieron todas:

```rman
STARTUP NOMOUNT;
RESTORE CONTROLFILE FROM AUTOBACKUP;   # o FROM '/opt/oracle/backup/<pieza del control file>'
ALTER DATABASE MOUNT;
RECOVER DATABASE;
ALTER DATABASE OPEN RESETLOGS;
```

Despues de un `OPEN RESETLOGS` empieza una encarnacion nueva: hacer un respaldo Full
de inmediato. `BACKUP CONTROLFILE TO TRACE` (SQL: `ALTER DATABASE BACKUP CONTROLFILE TO TRACE AS '/opt/oracle/backup/control.sql';`)
deja el `CREATE CONTROLFILE` como texto, util si no hubiera ninguna pieza.

### 5. Miembro o grupo de redo INACTIVO

```sql
SELECT group#, status, archived FROM v$log;
SELECT group#, member, status FROM v$logfile;          -- el perdido aparece INVALID
-- Un miembro perdido de un grupo multiplexado:
ALTER DATABASE DROP LOGFILE MEMBER '/ruta/redo01b.log';
ALTER DATABASE ADD LOGFILE MEMBER '/ruta/redo01b.log' TO GROUP 1;
-- Un grupo completo INACTIVO y ya archivado:
ALTER DATABASE CLEAR LOGFILE GROUP 1;
-- Si no estaba archivado (rompe la cadena: respaldar Full despues):
ALTER DATABASE CLEAR UNARCHIVED LOGFILE GROUP 1;
```

### 6. Grupo de redo CURRENT

Lo que solo estaba en ese grupo no se puede recuperar. Se vuelve al ultimo punto
consistente que permiten los archived logs:

```sql
SHUTDOWN ABORT;
STARTUP MOUNT;
SELECT group#, sequence#, status FROM v$log;   -- secuencia del grupo perdido, por ejemplo 52
```
```rman
RUN {
  SET UNTIL SEQUENCE 52 THREAD 1;   # recupera hasta la 51 inclusive
  RESTORE DATABASE;
  RECOVER DATABASE;
}
ALTER DATABASE OPEN RESETLOGS;
```

Alternativas equivalentes: `SET UNTIL TIME "TO_DATE('2026-10-12 10:00','YYYY-MM-DD HH24:MI')"`
o, en SQL*Plus, `RECOVER DATABASE UNTIL CANCEL` aplicando los archived logs hasta
el ultimo disponible y escribiendo `CANCEL`. Respaldar Full despues del RESETLOGS.

### 7. Archived logs perdidos

```rman
CROSSCHECK ARCHIVELOG ALL;
LIST EXPIRED ARCHIVELOG ALL;
DELETE EXPIRED ARCHIVELOG ALL;
BACKUP INCREMENTAL LEVEL 0 DATABASE PLUS ARCHIVELOG;   # cadena nueva
```

### 8. Perdida total

```rman
STARTUP FORCE NOMOUNT;            # RMAN arranca con parametros minimos si falta el SPFILE
SET DBID 1234567890;              # el DBID esta en el nombre del autobackup y en los logs de RMAN
RESTORE SPFILE FROM AUTOBACKUP;
STARTUP FORCE NOMOUNT;
RESTORE CONTROLFILE FROM AUTOBACKUP;
ALTER DATABASE MOUNT;
RESTORE DATABASE;
RECOVER DATABASE;
ALTER DATABASE OPEN RESETLOGS;
```

Si el autobackup no esta en la ruta por defecto, se indica antes con
`SET CONTROLFILE AUTOBACKUP FORMAT FOR DEVICE TYPE DISK TO '/opt/oracle/backup/%F';`.
Anotar el **DBID** antes del laboratorio: `SELECT dbid FROM v$database;`.

## Estrategias que dejan cubiertos los ocho escenarios

Con el gestor se crean asi (cada una genera su `RMA000x.rma` y su linea en el catalogo):

| Estrategia | Que | Como | Cuando | Cubre |
| --- | --- | --- | --- | --- |
| Datos criticos | Tablespaces `FREEPDB1:LAB_DATOS` (prioridad alta) y `FREEPDB1:USERS` (media) + archived logs + control file | Nivel 1 acumulativo | Todos los dias, 02:00 | 1, 3, 4 |
| Base completa | **Full**: base completa + redo en linea + archived logs + control file + SPFILE | Nivel 0 | Domingo 01:00 | 2, 3, 4, 8 y base de los nivel 1 |
| Redo archivado | Solo componentes: redo en linea + archived logs + control file | Copia de componentes | Cada 4 h | 6, 7 (achica la ventana de perdida) |

Ademas, fuera de RMAN: control files y redo en linea **multiplexados** (escenarios 4 y 5)
y el DBID anotado (escenario 8). Un `expdp` no reemplaza a estas estrategias: es una
exportacion logica y no permite recuperar hasta un punto en el tiempo.

## Fuentes

- Oracle Database Backup and Recovery User's Guide: *Performing Complete Database Recovery*,
  *Performing Flashback and Database Point-in-Time Recovery*, *Recovering from the Loss of
  Control Files* y *Recovering from Loss of Online Redo Log Files*.
- Clase del 05/10/2026 (V$RECOVER_FILE, RECOVER DATABASE AUTOMATIC, UNTIL TIME, UNTIL CANCEL).
- Prueba de recuperacion del proyecto: [evidencias/prueba-recuperacion.md](evidencias/prueba-recuperacion.md).
