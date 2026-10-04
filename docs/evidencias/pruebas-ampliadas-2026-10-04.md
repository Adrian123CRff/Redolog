# Pruebas locales ampliadas: 4 de octubre de 2026

## Resultado

Las pruebas pendientes del laboratorio terminaron correctamente:

- 41 pruebas automatizadas, cero fallos y errores.
- 51 comprobaciones del flujo ampliado real, todas aprobadas.
- 10 comprobaciones reales de recomendaciones y bitacora, todas aprobadas.
- Interfaz revisada con Playwright: modo LOCAL, historial persistente, apertura
  de evidencia RMAN y ausencia de errores JavaScript.

Las 61 comprobaciones reales no son 61 respaldos. Esta pasada genero ocho
respaldos y realizo dos recuperaciones de un tablespace exclusivo de prueba.
No hicieron falta nuevas correcciones del codigo Java en esta pasada.

## Entorno y seguridad

Se reanudaron las pruebas solicitadas por el usuario despues de la pausa.
Docker rman-lab y la aplicacion estaban detenidos; se iniciaron correctamente.
Oracle 23.26.1.0.0, base FREE en READ WRITE y ARCHIVELOG, PDB FREEPDB1.
La aplicacion escucha solamente en 127.0.0.1:8787, en modo LOCAL.

Se genero una copia completa nueva para incluir los objetos QA anteriores.
La recuperacion se limito a QA_RMAN_20261004161551, datafile 19 de 16 MiB.
Se creo un esquema sin autenticacion, sin horarios de respaldo automatico.
Las restauraciones escribieron archivos nuevos; no se eliminaron ni
sobrescribieron los datafiles originales ni los respaldos existentes.

No se desconecto LAB_DATOS ni se modifico LAB_DEMO.PEDIDOS. La comparacion exacta
de sus cinco filas antes y despues fue correcta, no solo el conteo.

## Respaldos y espacio medido

Todos los siguientes terminaron EXITOSO y con sus conjuntos identificados y
leidos por VALIDATE BACKUPSET. Los tamanos incluyen las piezas y autobackups
asociados a cada ejecucion; no solamente el archivo de datos.

| Caso real | MiB | Piezas |
| --- | ---: | ---: |
| Base completa FULL comprimida | 546.34 | 7 |
| Tablespace nivel 0 comprimido | 21.50 | 5 |
| Datafile nivel 1 diferencial sin compresion | 36.32 | 5 |
| Archived logs por separado | 20.55 | 2 |
| Datafile nivel 1 acumulativo comprimido | 20.52 | 5 |
| Archived logs, control file y SPFILE | 19.34 | 4 |
| Control file por separado | 19.14 | 2 |
| SPFILE por separado | 18.17 | 2 |
| Total nuevo | 701.88 | 32 |

La copia completa, incluida su verificacion, tardo unos 8 min 9 s.
Al finalizar se midieron 74 archivos y 1570.16 MiB en runtime/backups, alrededor
de 1.53 GiB contando pruebas anteriores. Quedaban aproximadamente 13.6 GiB libres
en C:. Esto no incluye el espacio adicional de los datafiles restaurados, el
disco virtual Docker, el catalogo H2 o los registros de ejecucion.

El diferencial sin compresion ocupa mas que el nivel 0 comprimido en esta prueba
pequena porque incluye componentes y autobackup. No inferir que todo incremental
siempre sera menor que cualquier FULL o nivel 0.

## Recuperaciones reales

| Caso | Resultado | Tiempo medido |
| --- | --- | ---: |
| Nivel 0 + diferencial + redo | Tres filas exactas, incluida la posterior al diferencial | 11.6 s |
| Nivel 0 + acumulativo + redo | Cinco filas exactas, incluida la posterior al acumulativo | 12.9 s |

El tiempo medido comprende la invocacion RESTORE/RECOVER y la vuelta ONLINE del
tablespace, no la preparacion ni toda la duracion del ensayo.

En ambos casos se comprobo primero que la consulta fallara con el tablespace
offline. RESTORE DATAFILE FROM TAG utilizo el nivel 0 y RECOVER leyo la pieza
incremental correspondiente. El log identifica QA_LEVEL1_20261004161551 para el
diferencial y QA_CUMULATIVE_20261004161551 para el acumulativo.

Los datos finales fueron las marcas MARCA_1 a MARCA_5, con importes 125, 250, 375,
500 y 625. Los cambios posteriores al respaldo se archivaron antes de recuperar.
RECOVER uso archived logs disponibles en el volumen persistente de Oracle;
no se simulo la perdida simultanea de dichos logs originales.

## Controles comprobados

- Rechazo de otro respaldo, de edicion y de liberacion mientras habia un trabajo.
- Rechazo de datafile inexistente, VALIDATE sin respaldo, operacion no admitida
  y ruta con traversal.
- Recomendacion de incluir archived logs visible antes de aplicarla.
- Aplicar recomendaciones cambia la configuracion y el script, exige nueva
  aprobacion y registra ambas decisiones en la bitacora.
- Intentar ejecutar el script cambiado sin aprobarlo es rechazado.
- La estrategia de recomendaciones no quedo programada ni produjo respaldos.
- Los casos de timeout, piezas ausentes o vacias, errores RMAN, catalogo alterado
  y reinicio del servicio se cubrieron con pruebas automatizadas aisladas.
  No se provoco corrupcion real ni llenado del disco del usuario.

## Validacion completa y limites

RESTORE DATABASE VALIDATE, RESTORE CONTROLFILE VALIDATE y RESTORE SPFILE VALIDATE
terminaron sin errores. RMAN leyo tres conjuntos de la copia completa, una copia
de datafile del QA y el autobackup seleccionado para control file/SPFILE.
Esta seleccion no se confunde con la verificacion individual de cada respaldo:
esa ya se hizo por las claves exactas de sus conjuntos.

No se restauro toda la instancia sobre un servidor nuevo, no se reemplazo el
control file o SPFILE activos y no se provoco una perdida total del contenedor.
Tampoco se probaron nube, carga prolongada o todas las combinaciones posibles.
Lo demostrado es el funcionamiento de los flujos locales y las dos cadenas
concretas de recuperacion descritas, no una certificacion de produccion.

## Estado final

Oracle READ WRITE/ARCHIVELOG; cero datafiles pendientes de recovery. LAB_DATOS y
los tres tablespaces QA estan ONLINE. No hay trabajos activos ni programados;
todas las estrategias QA estan desactivadas. Se conservaron los objetos QA,
sus piezas y el historial. Las alertas de las estrategias QA invalidas o sin
horario son consecuencia deliberada de los casos de prueba, no nuevos fallos.

Oracle y la aplicacion quedan encendidos para consulta. La programacion real
de Quartz ya se habia demostrado el 28/09; no se repitio un disparo horario en
esta pasada. Su evidencia esta en prueba-local-2026-09-28.md.

## Registros reproducibles

- [51 comprobaciones](../../runtime/pruebas-ampliadas/20261004161551/resultados.json).
- [Tamanos e identificadores de los ocho respaldos](../../runtime/pruebas-ampliadas/20261004161551/tamanos.json).
- [Recuperacion diferencial](../../runtime/pruebas-ampliadas/20261004161551/diferencial-restore-recover.log).
- [Recuperacion acumulativa](../../runtime/pruebas-ampliadas/20261004161551/acumulativo-restore-recover.log).
- [Validacion del plan completo](../../runtime/pruebas-ampliadas/20261004161551/restore-database-validate.log).
- [Salud final](../../runtime/pruebas-ampliadas/20261004161551/salud-final.log).
- [41 pruebas automatizadas](../../runtime/pruebas-ampliadas/20261004161551/pruebas-automatizadas.json).
- [10 comprobaciones de recomendaciones](../../runtime/pruebas-recomendaciones/20261004162948/resultados.json).

Scripts: scripts/lab/prueba-ampliada.ps1 y scripts/lab/prueba-recomendaciones.ps1.
Los JSON de cada respaldo contienen su script y registros; los archivos completos
tambien se conservan en runtime/executions bajo su identificador.
