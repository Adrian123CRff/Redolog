# Prueba local real con Docker y Oracle
Fecha local: 28 de septiembre de 2026, UTC-6.

## Resultado
El flujo principal ensayado funciono contra Oracle real: 20 comprobaciones
aprobadas de 20 en la repeticion final, y 33 pruebas automatizadas sin fallos.
No se usaron resultados simulados para acreditar los respaldos de esta prueba.

La aplicacion queda disponible en http://127.0.0.1:8787/ mientras permanezca
abierta la terminal que la inicio. El puerto 8788 corresponde a la demostracion
simulada anterior, no a este laboratorio.

## Entorno observado
- Contenedor: rman-lab, estado healthy.
- Oracle y RMAN: 23.26.1.0.0.
- Base FREE en READ WRITE y ARCHIVELOG; FREEPDB1 en READ WRITE.
- Alcance: FREEPDB1:LAB_DATOS, datafile 16 de 32 MiB.
- Modalidad: FULL comprimido, archived redo logs, control file y SPFILE.
- Destino persistente: /opt/oracle/backup, montado en runtime/backups.
- Verificacion: VALIDATE BACKUPSET sobre los conjuntos asociados a cada ejecucion.

## Comprobaciones
| Caso | Resultado |
| --- | --- |
| Conexion Oracle y disponibilidad de RMAN | Correcto |
| Laboratorio, modo de archivado, tablespace y espacio | Correcto |
| Vista previa de la estrategia | Sin errores impeditivos |
| Intento de respaldo sin aprobacion | Bloqueado, como corresponde |
| Destino inexistente | Aprobacion rechazada antes de ejecutar |
| Respaldo manual de laboratorio | EXITOSO, cinco piezas comprobadas |
| Verificacion posterior de sus conjuntos | Correcta |
| Verificacion manual independiente | EXITOSA y vinculada al respaldo correcto |
| Modificar horario | Invalida la aprobacion anterior |
| Hora del proximo disparo | Correcta |
| Quartz sin pulsar Ejecutar | Disparo a las 19:34:00, hora local |
| Respaldo programado y verificacion | EXITOSO, cinco piezas; unos 35 segundos |
| Hora planificada registrada | Coincide con la programada |
| Interfaz real e historial | Comprobados con Playwright, sin errores JavaScript |
| Datos despues de las pruebas | LAB_DEMO.PEDIDOS conserva cinco registros |
| Cierre de la prueba | Estrategias QA desactivadas y sin horarios |

Las 20 comprobaciones del script incluyen los controles de entorno y las
aserciones del flujo; no representan 20 respaldos distintos.

## Identificadores de la repeticion final
- Estrategia: QA_LOCAL_20260928-192946.
- ID estrategia: 64a6d730-6a95-4be8-9eac-c5017a232273.
- Respaldo manual: 678741db-6ba4-4fa1-ab53-b3d65dcb6583.
- Verificacion manual: 391e07b3-10e6-4d45-b767-27198df55a7d.
- Respaldo programado: 61ac047c-a2ec-4367-9cec-24ad0eaaa00a.
- Conjuntos del respaldo manual: 20, 21, 22, 23 y 24.
- Conjuntos del respaldo programado: 25, 26, 27, 28 y 29.

El respaldo programado inicio el 29/09 a las 01:34:00.342538 UTC y termino a las
01:34:35.769430900 UTC. En la zona local son las 19:34 del 28/09.

## Evidencia
[Resultados completos de las 20 comprobaciones](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/pruebas-locales/20260928-192946/resultados.json>).

[Respaldo manual y registros](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/pruebas-locales/20260928-192946/respaldo-manual.json>).

[Verificacion manual](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/pruebas-locales/20260928-192946/verificacion-manual.json>).

[Respaldo programado y registros](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/pruebas-locales/20260928-192946/respaldo-programado.json>).

[Conteo posterior de datos](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/pruebas-locales/20260928-192946/datos-despues.log>).

[Prueba repetible](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/scripts/lab/prueba-flujo-local.ps1>).

Los scripts exactos, salidas RMAN, comprobaciones de archivos y consultas de
conjuntos tambien se conservan en runtime/executions bajo el ID de cada ejecucion.

## Incidencias de la comprobacion
El usuario inicio la aplicacion porque el lanzador del asistente rechazo su
arranque. PowerShell requirio entrecomillar las opciones Java con puntos; el
comando comprobado figura en README.md.

La primera pasada respaldo y verifico correctamente, pero su asercion de horario
fallo por una conversion de fechas del script de prueba: PowerShell 7 habia
convertido ISO-8601 a DateTime UTC y Parse lo reinterpretaba como hora local.
Se corrigio el script para conservar la zona horaria y se repitio el flujo
completo con las 20 comprobaciones correctas. No fue un fallo de Quartz.

Tambien se ajusto el selector de la prueba visual para abrir la ejecucion por
su ID y no confundir una fila de verificacion con una de respaldo.

## Limites y precauciones
No se borraron archivos ni se hicieron RESTORE/RECOVER, cambios de modo de
archivado, recreaciones de Oracle o modificaciones de las tablas del laboratorio.
Los respaldos generados y el historial se conservaron. Las dos estrategias QA
creadas quedaron desactivadas; las estrategias anteriores del usuario no se
reaprobaron ni se modificaron.

Se verificaron tambien cuatro conjuntos antiguos, 9 a 12, sin errores, como
comprobacion previa. Esto no sustituye una prueba de recuperacion de la cadena.

La validacion real cubre el alcance y modalidad indicados. No demuestra todas las
combinaciones de datafiles, bases completas e incrementales, ni recuperacion ante
perdida del servidor. Tampoco constituye una validacion de despliegue en nube.

El disco del equipo estaba al 97% de uso y termino con aproximadamente 15.2 GiB
libres. Antes de aumentar la frecuencia de respaldos, revisar capacidad y politica
de conservacion; no se elimino ningun respaldo automaticamente.

