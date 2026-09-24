# Revision del avance contra el enunciado oficial

Fecha: 23 de septiembre de 2026.
Enunciado: "Desarrollo de una Herramienta para la Gestion de Estrategias de Respaldo
de Bases de Datos Oracle", EIF402, II ciclo 2026.

El enunciado reemplaza las suposiciones de requisitos-proyecto.md. Donde ambos
difieren, manda el enunciado.

## 1. Lo que ya estaba bien

- RMAN como mecanismo de ejecucion, Java y un laboratorio Oracle en Docker en
  ARCHIVELOG con RMAN disponible.
- Registro de bases y estrategias con nombre, prioridad y estado activo/inactivo.
- Los cuatro tipos pedidos: completo, incremental nivel 0, nivel 1 diferencial y
  nivel 1 acumulativo, separados del alcance.
- Generacion del script, vista previa, ejecucion programada con Quartz y
  reprogramacion al iniciar desde el catalogo H2.
- Registro de cada ejecucion con el script exacto y el log; control de ejecuciones
  duplicadas por ocurrencia y bloqueo por base; estado INCIERTO tras una caida.
- Deteccion de errores ORA-/RMAN: la ejecucion fallida del 22/09 quedo registrada
  como fallida y no como correcta.
- Las precisiones de requisitos-proyecto.md (un FULL no es base de incrementales,
  archived vs. online redo logs, un log no demuestra exito) coinciden con el
  enunciado (seccion 14.7).

## 2. Brechas encontradas y correccion aplicada

| Seccion | Pedia el enunciado | Como estaba | Correccion |
| --- | --- | --- | --- |
| 4.1 | Base, tablespaces, datafiles, control file, SPFILE, archived logs | Solo base o tablespaces; control file y SPFILE en una casilla; sin datafiles | Alcance base / tablespaces / datafiles / solo componentes; control file, SPFILE y archived logs por separado |
| 4.1 | Criterios para asignar la prioridad | Alta/Media/Baja sin criterio | Objetivo de antiguedad maxima del ultimo respaldo correcto: 24 / 72 / 168 h. Lo usan la validacion y las alertas |
| 5 | Tipo, nivel, compresion, opciones | Sin compresion ni opciones | Compresion (AS COMPRESSED BACKUPSET), nivel visible y verificacion posterior opcional |
| 6 | Distinguir informativa, advertencia y recomendacion; no cambiar el modo | Diagnostico "listo/no listo", solo en memoria | Estado de la base persistido; cuatro niveles con los textos del enunciado; la herramienta nunca cambia el modo |
| 7 | Descripcion, responsable, fecha de inicio, frecuencia, dias, intervalo, ventana, destino, dispositivo, espacio | Solo horas diarias; destino fijo | Constructor en cinco pasos con el script generado en vivo |
| 8 | Visualizacion y aprobacion antes de programar | Sin aprobacion | Aprobacion ligada a la huella SHA-256 del script; si la configuracion cambia, la aprobacion se invalida y la estrategia deja de programarse |
| 9 | Relacion estrategia, programacion, script y ejecucion | Horarios perdidos descartados sin aviso | Cada ejecucion guarda la huella del script; las ocurrencias no ejecutadas se detectan |
| 10 | Campos de evidencia; Exitoso / Con advertencias / Fallido | CORRECTA/FALLIDA; sin tipo, ubicacion ni error real | Todos los campos, piezas con tamano, lineas RMAN/ORA y los tres estados |
| 11 | Nueve condiciones de alerta | Ninguna | Todas implementadas (ver analisis-y-diseno.md, seccion 6) |
| 14.4 | Validar antes de generar o ejecutar | Solo formato de los campos | Valida tablespaces, datafiles, destino, espacio y modo de archivado contra la base |
| 14.7-14.8 | No asumir exito; considerar la existencia del archivo | Exito = codigo 0 sin ORA- | Se comprueba en el servidor que cada pieza existe; si falta alguna, la ejecucion es Fallida |
| 14.9 | Mecanismos de RMAN para verificar | RESTORE VALIDATE manual | CROSSCHECK + RESTORE ... VALIDATE, manual o al terminar cada respaldo |

## 3. Errores concretos corregidos

1. Causa del fallo del 22/09 (RMAN-06019, tablespace LAB_DATOS): el tablespace no
   existia. scripts/lab/01-datos.sql no podia crearlo: abria un PDB ya abierto y
   usaba "datafile size 32M" sin OMF configurado (ORA-02236). Se reescribio
   idempotente y con ruta explicita; ya se aplico en rman-lab.
2. El script ejecutaba dos veces "BACKUP ARCHIVELOG ALL", que copia todos los
   archived logs en cada ejecucion. Ahora usa "NOT BACKED UP 1 TIMES" despues de
   los datos (RMAN archiva el redo actual por si mismo), el control file va al
   final y cada estrategia tiene su TAG.
3. Una ejecucion INCIERTA bloqueaba la base para siempre, sin forma de liberarla.
   Ahora hay una accion "Liberar base" con confirmacion y registro en bitacora.
4. El modelo prohibia activar una estrategia sin horario, lo que impedia detectar
   la condicion "estrategia sin programacion" que pide el enunciado. Ahora se
   permite y se reporta.
5. Zona horaria America/Guatemala cambiada a America/Costa_Rica (mismo UTC-6).
6. No habia pruebas automatizadas, aunque el reporte de la migracion a Java 25
   decia TESTS_OK. Se agregaron 15 pruebas (script, validacion, alertas, horarios,
   compatibilidad con el catalogo anterior).
7. Los errores de validacion llegaban a la interfaz con el texto interno de
   Jackson. Ahora se muestra el mensaje original.

El catalogo existente se conserva: EST001 y las ejecuciones anteriores se leen con
el modelo nuevo (CORRECTA pasa a EXITOSO; la antigua casilla "control file y
SPFILE" activa ambos).

## 4. Pruebas realizadas contra rman-lab (23/09/2026)

| Prueba | Resultado |
| --- | --- |
| Comprobacion de la base | 23.26.1.0.0, ARCHIVELOG, FREEPDB1 READ WRITE, 21.8 GB libres en /opt/oracle/backup |
| EST001: FULL comprimido de FREEPDB1:LAB_DATOS + archived logs + control file + SPFILE, con verificacion | Exitoso en 40 s, 5 piezas comprobadas; CROSSCHECK y RESTORE ... VALIDATE sin errores |
| EST003: nivel 1 acumulativo de FREEPDB1:USERS sin nivel 0 previo | Exitoso. RMAN 23ai no hace un nivel 0 automatico: copia los bloques desde la creacion del datafile y lo registra como nivel 1. El monitor recomienda crear el nivel 0 |
| EST900: destino inexistente (simulacion controlada de error) | Fallido con el error real ORA-19504; queda en el historial como evidencia |
| EST002: nivel 0 semanal de la base completa | Guardada y pendiente de aprobacion. Advierte frecuencia insuficiente (168 h frente al objetivo de 72 h) |

Con esto hay evidencia de: ejecucion exitosa, ejecucion fallida, historial, script
generado y condiciones de advertencia. La evidencia de "aplicacion de una
recomendacion" se genero el 23/09 desde el monitor sobre EST003
("Incorporar archived redo logs"). El script cambio, la aprobacion se invalido y se
volvio a aprobar; ambos pasos quedaron en la bitacora.

Prueba de recuperacion (seccion 14.10), tambien el 23/09: perdida del datafile de
FREEPDB1:LAB_DATOS, RESTORE y RECOVER con archived logs, en 12.8 s y sin perdida de
datos. Detalle en evidencias/prueba-recuperacion.md.

EST900 y EST002 se crearon para estas pruebas; se pueden eliminar desde la vista
Estrategias sin perder el historial.

## 5. Hallazgos del laboratorio (ya corregidos)

- El autobackup del control file y el SPFILE se escribia en
  /opt/oracle/product/26ai/dbhomeFree/dbs, que se pierde si se recrea el
  contenedor. Ahora va a /opt/oracle/backup:
  `CONFIGURE CONTROLFILE AUTOBACKUP FORMAT FOR DEVICE TYPE DISK TO '/opt/oracle/backup/%F';`
- Los archived logs tambien se guardaban en $ORACLE_HOME/dbs. Ahora van al volumen
  persistente: `log_archive_dest_1 = LOCATION=/opt/oracle/oradata/FREE/archivelog`.
- Ambos ajustes se aplicaron en rman-lab y los aplica scripts/preparar-entorno.ps1
  (y la version .sh) en cada equipo.

En el laboratorio, los respaldos quedan en el mismo disco que la base
(runtime/backups). Sirve para practicar, pero no protege contra una falla del
disco; figura como limitacion en analisis-y-diseno.md.

## 6. Propuesta del monitor

La vista inicial "Monitor" cubre los pasos "Monitoreo" y "Control preventivo" de
la seccion 15 del enunciado. Responde en una pantalla: si hay algo que atender, si
los datos estan protegidos segun su prioridad y que se ejecuta despues.

1. Indicadores: estrategias en operacion (programadas y aprobadas), ultimo
   respaldo correcto, resultados de 7 dias y alertas abiertas.
2. Linea de tiempo de las ultimas y proximas 24 h, con un carril por estrategia.
   Es la linea T0, T1, T2... de la pizarra de clase. Cada estado usa forma y color
   (circulo exitoso, triangulo con advertencias, cuadrado fallido, circulo
   punteado no ejecutado, circulo vacio programado). Al pulsar un respaldo se
   abre su evidencia.
3. Control preventivo: alertas, advertencias, recomendaciones e informativas,
   filtrables, cada una con su accion (aprobar, editar, verificar, aplicar
   recomendacion, liberar base). La herramienta no aplica nada por si misma.
4. Estado por estrategia: antiguedad del ultimo respaldo correcto frente al
   objetivo de su prioridad, proxima ejecucion y ciclo de la estrategia.
5. Bases de datos (modo de archivado, version, espacio) y bitacora de acciones.

Dos apoyos visuales explican el modelo a quien no conoce RMAN:

- Ciclo de la estrategia (seccion 15): configurada, aprobada, programada,
  ejecutada, verificada. Cada paso es un punto con icono y el siguiente paso
  pendiente es un enlace (aprobar, activar, verificar).
- "Como se recuperaria" (seccion 5), en el paso Como respaldar del constructor.
  Con el tipo elegido muestra que piezas restauraria RMAN si la base fallara un
  viernes: diferencial, nivel 0 mas cinco nivel 1; acumulativo, nivel 0 mas el
  ultimo nivel 1. Tambien indica si los archived logs permiten llegar hasta el
  momento del fallo.

Se eligio este diseno porque el enunciado evalua el control preventivo. Un tablero
que solo lista ejecuciones muestra lo que ya ocurrio. Este muestra ademas lo que
va a fallar (script sin aprobar, destino inexistente, frecuencia insuficiente) y
lo que no ocurrio (respaldos programados que no se ejecutaron).

## 7. Estado de los pendientes

| Pendiente | Estado |
| --- | --- |
| Documento de analisis y diseno (entregables 1 y 2) | analisis-y-diseno.md: objetivos, requerimientos, comparaciones, diagramas, interfaz y limitaciones. Falta pasarlo al formato de entrega |
| Prueba de recuperacion (14.10) | Hecha; ver evidencias/prueba-recuperacion.md y scripts/lab/prueba-recuperacion.ps1 |
| Evidencia de aplicacion de una recomendacion | Hecha (EST003, bitacora) |
| Advertencia NOARCHIVELOG | Se ve en el modo simulacion (base "Contabilidad en NOARCHIVELOG"). En el laboratorio real haria falta una base en ese modo |
| Proyecto ejecutable por otros integrantes | README.md y scripts/preparar-entorno.ps1 / .sh |
| Publicacion gratuita | Modo simulacion en Render; ver despliegue.md |
| Correo al DBA | Opcional; se menciono en clase, pero el enunciado solo pide alertas |
| Ejecucion con la aplicacion cerrada | Los horarios viven en Quartz en memoria; si la aplicacion esta cerrada se reportan como "no ejecutados". El enunciado permite Windows Task Scheduler si el profesor lo exige |

## Como ejecutar

Ver README.md en la raiz del repositorio: `scripts/preparar-entorno.ps1 -Iniciar`
prepara el laboratorio, compila y abre http://127.0.0.1:8787.
