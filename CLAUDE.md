# Gestor de estrategias de respaldo RMAN (curso de Administracion de Bases de Datos)

Responder siempre en espanol. Proyecto Java 21+ / Maven sin frameworks web: servidor HTTP propio
(`edu.respaldos.Main`), interfaz en `src/main/resources/web` y Oracle Free en Docker como laboratorio.

## Arranque (Windows, desde la raiz)

- Laboratorio, datos de prueba, redo multiplexado y compilacion: `scripts\preparar-entorno.ps1` (`-Iniciar` abre ademas la app y el Ejecutor).
- Compilar y probar: `.\mvnw.cmd package` (las pruebas no necesitan Oracle).
- App: `java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar target/gestor-rman-0.1.0.jar` y abrir http://127.0.0.1:8787
- Ejecutor (otra terminal): `java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor`; una estrategia ya: `... edu.respaldos.Ejecutor --ahora RMA0001`
- Consolas del laboratorio: `docker exec -it -u oracle rman-lab sqlplus / as sysdba` y `docker exec -it -u oracle rman-lab rman target /`

`runtime/` (catalogo H2, respaldos, logs, scripts .rma) es local de cada equipo y no se versiona.
No guardar contrasenas SMTP en archivos: el correo usa variables `GESTOR_SMTP_*`.

## Lo que pidio el profesor (clase 05/10) y donde esta

Detalle en `docs/cambios-clase-2026-10-05.md`. Resumen:
- Constructor: varios tablespaces con prioridad propia, control file, archived logs, redo en linea, boton Full, completo/incremental, dias y horas, ciclica o no, base.
- Al aprobar: script `runtime/scripts/RMA000N.rma` y una linea en el catalogo plano `runtime/catalogo-estrategias.txt`.
- **Ejecutor aparte** (`Ejecutor.java`): lee el catalogo, llama a `rman ... cmdfile= log=`, anota pieza y log. En modo local la app NO corre horarios (planificador EXTERNO por defecto); tras una suspension corre solo el horario mas reciente.
- Modales del script, del catalogo y del log; "Volver a ejecutar ahora"; correo al DBA.
- Fallos de medio fisico (8 escenarios con RESTORE/RECOVER): `docs/fallos-medio-fisico.md`.

## Laboratorio de caida (el profesor rompe la base y hay que recuperarla)

- Regla: primero diagnosticar, despues restaurar. Hoja de consulta: `docs/hoja-rapida-lunes.md`.
- En la app, Bases de datos: **Diagnosticar fallos** (`RecoveryAdvisor.java`) solo lee y propone el paso a paso con
  comandos para SQL*Plus/RMAN/PowerShell; **Tomar foto del estado** guarda SCN y filas por tabla para detectar
  filas o tablas perdidas. La recuperacion la ejecuta el DBA, no la app.
- Estado del laboratorio `rman-lab`: CDB `FREE` con PDB `FREEPDB1`, tablespace `LAB_DATOS`, tabla `LAB_DEMO.PEDIDOS`,
  ARCHIVELOG, autobackup del control file en `/opt/oracle/backup/%F` (DBID 1516667409), redo con 2 miembros por grupo
  (`FREE/redo0N.log` y `espejo/redo0Nb.log`), respaldos en `runtime/backups` del host.
- Antes de la prueba: `Ejecutor --ahora RMA0002` (nivel 0) y `--ahora RMA0001`, luego Tomar foto del estado.
- Despues de un `OPEN RESETLOGS`, respaldo completo de inmediato.

## Ramas

`cambios-profesor-local` tiene todo lo anterior; `master` todavia no. No hacer push a `master` ni a
`kenny-correo-rma-robot` sin que lo pida el usuario.
