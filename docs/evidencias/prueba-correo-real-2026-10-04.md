# Evidencia: respaldo real, fallo real y aviso por correo al DBA

Fecha: 4 de octubre de 2026 (hora de Costa Rica, UTC-6).
Rama: `kenny-correo-rma-robot`.

Esta prueba comprueba la cadena completa que el profesor describio en clase: un
respaldo falla en RMAN, la herramienta lo detecta al auditar la salida y envia un
correo al DBA con el problema. Se ejecuto contra Oracle y RMAN reales. El servidor de
correo fue un servidor SMTP simulado en la misma maquina (ver Limitaciones).

## Entorno

| Elemento | Valor |
| --- | --- |
| Base | Oracle AI Database 26ai Free 23.26.1.0.0 |
| Contenedor | `rman-kenny`, creado con `docker commit` a partir de `Oracle-kenny` (el original no se modifico) |
| Modo de archivado | ARCHIVELOG, base abierta en READ WRITE |
| Tablespace respaldado | `FREEPDB1:USERS` |
| Aplicacion | Modo local, puerto 8796, catalogo temporal separado del laboratorio del grupo |
| Correo | Variables `GESTOR_SMTP_*` apuntando a un servidor SMTP simulado en 127.0.0.1:2525 |
| Correo del DBA | `dba@una.cr`, registrado en la base "Kenny (copia)" |

Diagnostico desde la aplicacion: Oracle y RMAN disponibles, ARCHIVELOG,
"Conexion correcta; base en ARCHIVELOG".

## Estrategias de la prueba

Ambas: alcance tablespace `FREEPDB1:USERS`, tipo completo comprimido, archived logs,
control file y SPFILE, verificacion posterior activada, prioridad ALTA. Se
previsualizaron (sin errores), se aprobaron y luego se ejecutaron de forma manual.

| Estrategia | Destino | Que busca demostrar |
| --- | --- | --- |
| EST100 OK | `/opt/oracle/backup` | Ejecucion exitosa |
| EST200 SIN PERMISO | `/opt/oracle/backup-ro` (directorio de root, solo lectura) | Fallo real de RMAN que supera la validacion previa, porque el destino existe pero Oracle no puede escribir en el |

## Resultado 1: ejecucion exitosa (EST100)

- Ejecucion: `4884716a-50dc-410f-8fc1-def222e645e1`.
- Estado: **EXITOSO**. Mensaje: "Respaldo realizado. 5 pieza(s) comprobadas en
  /opt/oracle/backup."
- Piezas comprobadas en disco, entre ellas
  `/opt/oracle/backup/FREE_20261005_01m8ef8m_1_1_1.bkp` (1.2 MB) y
  `/opt/oracle/backup/FREE_20261005_02t8ef8t_2_1_1.bkp` (403.5 MB).
- Conjuntos identificados: 1, 2, 3, 4 y 5. Verificado: si.
- Detalle: "Verificacion posterior correcta: conjuntos [1, 2, 3, 4, 5] leidos por
  VALIDATE BACKUPSET."
- No se envio ningun correo, como corresponde a una ejecucion exitosa.

## Resultado 2: ejecucion fallida (EST200)

- Ejecucion: `966a10b9-8fc4-43e4-b389-529a0716b5b2`.
- Estado: **FALLIDO**. Mensaje: `RMAN informo un error: ORA-19504: failed to create
  file "/opt/oracle/backup-ro/FREE_20261005_06rcefcr_6_1_1.bkp"`.
- Detalle registrado desde la salida real de RMAN:
  - `RMAN-03009: failure of backup command on d1 channel at 10/05/2026 05:46:04`
  - `ORA-19504: failed to create file "/opt/oracle/backup-ro/FREE_20261005_06rcefcr_6_1_1.bkp"`
  - `ORA-27040: file create error, unable to create file`
- Sin piezas, sin conjuntos y sin verificacion.

## Resultado 3: correo recibido por el DBA

El servidor SMTP recibio exactamente un correo, el del fallo:

```text
From: robot@una.cr
To: dba@una.cr
Subject: [RMAN] FALLIDO - EST200 SIN PERMISO en Kenny (copia)

Base de datos: Kenny (copia) (rman-kenny)
Estrategia: EST200 SIN PERMISO
Tipo: Completo (FULL), comprimido, verificacion posterior | tablespaces FREEPDB1:USERS + archived redo logs + control file + SPFILE
Estado: FALLIDO
Inicio: 2026-10-05T05:45:57.363039600Z
Fin: 2026-10-05T05:46:04.613775100Z
Mensaje: RMAN informo un error: ORA-19504: failed to create file "/opt/oracle/backup-ro/FREE_20261005_06rcefcr_6_1_1.bkp"

Detalle de RMAN:
  RMAN-03009: failure of backup command on d1 channel at 10/05/2026 05:46:04
  ORA-19504: failed to create file "/opt/oracle/backup-ro/FREE_20261005_06rcefcr_6_1_1.bkp"
  ORA-27040: file create error, unable to create file

Registro completo: <ruta>\executions\966a10b9-8fc4-43e4-b389-529a0716b5b2\output.log
```

La bitacora de la aplicacion registro `NOTIFICACION_ENVIADA | Enviado a dba@una.cr en
el intento 1.`

## Scripts .rma generados

En `runtime/scripts/` quedaron los scripts vigentes de cada estrategia:

- `EST100_OK-rman-kenny.rma`
- `EST200_SIN_PERMISO-rman-kenny.rma`

Cada ejecucion conserva ademas su propia copia (`script.rma`, `verify.rma`) junto al
registro de RMAN.

## Pruebas automaticas asociadas

La suite completa (77 pruebas) pasa sin fallos. Las nuevas cubren el aviso por correo
con un servidor simulado (envio, no envio en exito, reintentos, correo faltante,
inyeccion de cabeceras), la configuracion SMTP, el campo del correo del DBA, los
archivos `.rma` y la lectura de ejecuciones guardadas con la extension anterior.

## Limitaciones

- El correo se envio a un servidor SMTP simulado local, no a Gmail ni a un servidor
  institucional. Se comprobo el protocolo, el remitente, el destinatario y la
  codificacion del asunto, pero no la autenticacion ni el TLS de un servidor real.
- No es una prueba de recuperacion: no se restauro ni se recupero la base.
- El laboratorio `rman-lab` del instalador del grupo no se pudo crear en esta
  maquina: Oracle crea la base pero el script de arranque de la imagen informa
  "Database configuration failed" y el contenedor se detiene, dos veces seguidas. No
  se encontro la causa; el log de DBCA indica `SUCCEEDED`. Por eso la prueba uso una
  copia de `Oracle-kenny`.
- El puerto 1521 de esta maquina esta ocupado por un listener de Oracle instalado en
  Windows, por lo que el contenedor original no arranca con su puerto publicado.
- El fallo se provoco con un destino sin permiso de escritura. Es un error real de
  RMAN, pero no cubre todos los tipos de fallo posibles.
