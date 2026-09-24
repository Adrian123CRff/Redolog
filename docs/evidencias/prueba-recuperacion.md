# Evidencia: prueba de recuperacion en el laboratorio

Fecha: 23 de septiembre de 2026, 21:53 (America/Costa_Rica).
Ambiente: contenedor rman-lab (Oracle AI Database 26ai Free 23.26.1, CDB FREE,
PDB FREEPDB1, ARCHIVELOG). Es un ambiente controlado de pruebas (enunciado 14.3).
Script reproducible: scripts/lab/prueba-recuperacion.ps1.

## Escenario

Perdida del datafile del tablespace FREEPDB1:LAB_DATOS con la base abierta. Antes de
la falla se inserto un pedido nuevo, posterior al ultimo respaldo. Tambien se
forzaron cambios de log para que ese pedido solo existiera en los archived redo
logs. Asi la prueba demuestra que la estrategia recupera hasta el momento de la
falla, no solo hasta el ultimo respaldo (enunciado 14.10).

## Resultado

| Dato | Valor |
| --- | --- |
| Resultado | Exitosa: no se perdieron datos |
| Respaldo usado | Pieza FREE_20260924_0an07jkn_10_1_1.bkp, TAG EST001_DATOS_DEL_LABORATORIO (estrategia EST001) |
| Error durante la falla | ORA-00376: file 16 cannot be read at this time |
| Redo aplicado | Archived logs, secuencias 6 a 11 |
| Filas antes del cambio / esperadas / recuperadas | 4 / 5 / 5 |
| Pedido posterior al respaldo recuperado | Si |
| Tiempo de RESTORE + RECOVER | 10 s |
| Tiempo fuera de servicio del tablespace | 12.8 s |

## Pasos y salida relevante

1. Verificacion previa sin danar nada: `RESTORE TABLESPACE FREEPDB1:LAB_DATOS VALIDATE`.
2. Cambio posterior al respaldo e `ALTER SYSTEM ARCHIVE LOG CURRENT` repetido.
3. Falla simulada: `ALTER TABLESPACE LAB_DATOS OFFLINE IMMEDIATE` y datafile
   renombrado. La consulta devuelve:

       ORA-00376: file 16 cannot be read at this time
       ORA-01110: data file 16: '/opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf'

4. Restauracion y recuperacion:

       RMAN> RESTORE TABLESPACE FREEPDB1:LAB_DATOS;
       channel ORA_DISK_1: restoring datafile 00016 to /opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf
       channel ORA_DISK_1: reading from backup piece /opt/oracle/backup/FREE_20260924_0an07jkn_10_1_1.bkp
       channel ORA_DISK_1: restore complete, elapsed time: 00:00:01
       RMAN> RECOVER TABLESPACE FREEPDB1:LAB_DATOS;
       starting media recovery
       archived log file name=.../arch1_6_1244610772.dbf thread=1 sequence=6
       ...
       archived log file name=.../arch1_9_1244610772.dbf thread=1 sequence=9
       media recovery complete, elapsed time: 00:00:01

5. `ALTER TABLESPACE LAB_DATOS ONLINE` y conteo de filas: 5, incluido el pedido
   posterior al respaldo.

## Hallazgos que dejo la prueba

- Los archived logs se guardaban en $ORACLE_HOME/dbs, fuera del volumen
  persistente. Desde el 24/09/2026 el laboratorio usa
  `log_archive_dest_1 = LOCATION=/opt/oracle/oradata/FREE/archivelog`, y
  scripts/preparar-entorno.ps1 (y su version .sh) lo configura en cada equipo.
- Con el mismo criterio, el autobackup del control file se envia a
  /opt/oracle/backup (`CONFIGURE CONTROLFILE AUTOBACKUP FORMAT ... '%F'`).
- RMAN eligio por si mismo el respaldo mas reciente del tablespace. La herramienta
  solo tiene que garantizar que exista y sea legible; eso lo comprueban la
  verificacion posterior y la recomendacion "verificar el ultimo respaldo".
