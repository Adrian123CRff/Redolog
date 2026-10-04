# Correcciones de la revision del enunciado

Fecha: 4 de octubre de 2026. Este informe actualiza la revision historica
[cumplimiento-enunciado-2026-10-04.md](cumplimiento-enunciado-2026-10-04.md).
El PDF del profesor no se modifico.

## Estado de cierre

Los defectos encontrados se corrigieron en el codigo y se cubrieron con pruebas
automatizadas. Compilacion y empaquetado verificados con `mvn package`: 56 pruebas,
cero fallos, cero errores, cero omitidas; 15 casos nuevos respecto a las 41 anteriores.

**Cierre operativo, 04/10 a las 17:43, America/Guatemala:** tras la nueva solicitud
del usuario se reinicio el servidor y se confirmo el contrato `valid` del JAR
corregido en 8787, modo LOCAL. Pasaron las 23 comprobaciones reales del flujo
local y las 13 comprobaciones de navegador. No quedan ejecuciones activas ni
estrategias programadas. El horario QA se desactivo al terminar.

No se borraron estrategias, historial ni respaldos. La regresion genero dos
respaldos nivel 0 pequenos de LAB_DATOS con componentes y verificaciones RMAN;
no se ejecutaron restauraciones ni escrituras sobre los datos de negocio.

## Cambios y verificacion

| Problema | Solucion aplicada | Verificacion automatizada |
| --- | --- | --- |
| COMPONENTS con cero espacio no advertia | ESPACIO_AGOTADO bloquea aprobacion y ejecucion para todo alcance; cero se muestra como 0 KB | RmanScriptTest y BackupServiceTest |
| Tamano de componentes tratado como cero | ESTIMACION_PARCIAL explica que no se conoce su volumen; la suma de datafiles no certifica capacidad | RmanScriptTest y AlertsTest |
| Borrador generado aunque habia errores | Preview valida primero, devuelve valid=false y script/huella vacios ante ERROR; script.rman solo se escribe despues de validar antes de ejecutar | BackupServiceTest |
| Vista previa podia conservar un script desactualizado | Se limpia al editar; contador de peticiones descarta respuestas antiguas; revision bloqueada no marca validacion completada | Navegador: errores semanticos/sintacticos, respuestas fuera de orden y huella actual comprobados |
| Antiguedad solo controlada con horario activo y aprobado | SIN_RESPALDO_RECIENTE incluye manuales, inactivas y sin aprobar; nueva aprobacion no borra antiguedad | AlertsTest: umbral exacto, un segundo excedido, fecha futura y sin evidencia |
| Nivel 0 configurado confundido con base respaldada | Se exige copia nivel 0 correcta y verificada, conjuntos/piezas y datafiles del diagnostico previo compatibles | ExecutionEvidenceTest: 6 casos |
| Evidencia antigua sin lista de datafiles | Campo opcional compatible con JSON previo; no se inventan listas; nuevas copias y verificaciones conservan el dato | RmanScriptTest y BackupServiceTest, incluida persistencia tras reinicio del servicio aislado |
| Ventana y textos imprecisos | Etiqueta de duracion maxima advertida; incluye verificacion posterior, compara duracion sin truncar minutos; no detiene RMAN | AlertsTest y documentacion actualizada |
| Documentacion solo cron/verificacion generica | Se distingue CronTrigger, SimpleTrigger continuo y VALIDATE BACKUPSET para evidencia concreta | Contraste de analisis-y-diseno.md con implementacion |

La funcion canonica que calcula huellas sigue disponible internamente para
comparar aprobaciones. Esto no expone ni guarda un script operativo invalido.
La comprobacion previa a aprobar y ejecutar refresca el diagnostico; la vista
previa usa el ultimo diagnostico disponible.

## Revision punto por punto

Los estados siguientes corresponden al prototipo academico, no a una certificacion
de produccion ni a una calificacion del profesor.

| Punto | Que se exige | Estado y sustento actual |
| --- | --- | --- |
| 1 | Herramienta para gestionar estrategias Oracle RMAN | Implementado: modelo de estrategias, generador, aprobacion, planificador y evidencia, no solo scripts |
| 2 | Planificar, automatizar y comprobar respaldos | Flujo repetido con version corregida: respaldo manual y por horario exitosos y verificados |
| 3 | Control preventivo y aporte a disponibilidad/integridad | Implementado y explicado en analisis-y-diseno; se ampliaron controles de espacio y antiguedad |
| 4 | Alcances y prioridades justificadas | Base, tablespaces, datafiles y componentes; criterios del grupo y umbrales 24/72/168 h documentados; ocho respaldos reales previos |
| 5 | FULL, L0, diferencial y acumulativo; siete criterios de comparacion | Implementado y documentado; dos recuperaciones QA previas; alerta L0 ahora respaldada por evidencia historica, no mera configuracion |
| 6 | ARCHIVELOG/NOARCHIVELOG y decision explicita | Implementado; ARCHIVELOG real, NOARCHIVELOG en pruebas aisladas; no se altera automaticamente el modo de Oracle |
| 7 | Campos, alcance, metodo, programacion, ventana, destino y espacio | Implementado; corregido espacio cero y volumen desconocido. Ventana significa duracion maxima advertida; confirmar interpretacion con el profesor |
| 8 | Validar, construir, visualizar, aprobar, programar y ejecutar | Corregido el orden externo: configuracion invalida no devuelve script; aprobacion y ejecucion refrescan diagnostico. Comprobado por API y navegador |
| 9 | Automatizacion con trazabilidad | Quartz disparo nuevamente el 04/10 a las 17:42 locales; origen HORARIO, hora planificada y verificacion correctos |
| 10 | Evidencia, estados y consulta de historial | Implementado; persistencia y conservacion comprobadas en pruebas automatizadas. EXITOSO, CON_ADVERTENCIAS, FALLIDO y estados de control adicionales |
| 11 | Nueve condiciones preventivas | Reglas implementadas; corregidas las brechas de espacio y respaldo reciente. Los limites de evidencia incremental estan expresos |
| 12 | Arquitectura conceptual | Documentada con navegador, Java, Quartz, H2, Docker, RMAN y Oracle; etiquetas del planificador corregidas |
| 13 | Analisis, diseno, prototipo, seis evidencias y presentacion | Analisis/diseno actualizados y seis evidencias reunidas. Pendiente adaptar/ensayar exposicion y cualquier formato institucional que indique el profesor |
| 14 | Laboratorio, validacion, revision y exito comprobado | Bloqueos y evidencia automatizada verificados; recuperacion QA previa conservada. No se repitio perdida total ni se afirma recuperabilidad universal |
| 15 | Flujo integrado de proteccion y monitoreo | Implementado; pruebas de servicio y regresion Docker de la version corregida completadas |
| 16 | Explicar como reduce riesgos | Explicacion documentada y guia de demostracion disponible; el ensayo oral del grupo todavia no se realizo |

## Paquete de seis evidencias

Exportado de la API mediante consultas, sin acciones de ejecucion, a
[runtime/entrega-evidencias/20261004-172121](../runtime/entrega-evidencias/20261004-172121).
Incluye manifiesto de fechas y SHA-256 de cada archivo exportado.

1. `01-respaldo-exitoso.json`: FULL real del 04/10, ejecucion
   36fb774f-2022-4385-ae8e-42af8432af0d, con piezas y verificacion.
2. `02-fallo-historico.json`: error RMAN real del 23/09, ejecucion
   1c3cacb3-5db8-4eb1-84e1-5c33690cc445. Es de una version anterior; el destino
   inexistente se rechaza hoy antes de ejecutar, no se reprodujo ese fallo.
3. `03-historial.json`: ejecuciones consultadas, con sus fechas y estados.
4. `04-script-ejecutado.rman`: script exacto del respaldo exitoso seleccionado.
5. `05-advertencia.json`: estrategia deliberadamente inactiva; la advertencia
   no significa que su respaldo anterior haya fallado.
6. `06-recomendacion-aplicada.json`: eventos de incorporacion de archived logs
   y verificacion, estrategia afectada y diez comprobaciones del 04/10.

Estos archivos son evidencia historica identificada, no seis pruebas nuevas del
codigo corregido. Se pueden volver a exportar con
`./scripts/lab/exportar-evidencias.ps1`.

## Regresion real completada

Resultados: [23 comprobaciones locales](../runtime/pruebas-locales/20261004-173857/resultados.json)
y [13 comprobaciones del navegador](../runtime/pruebas-ui/2026-10-04T23-41-43-824Z/resultados.json).
Estrategia QA: 55ae2e41-60c5-4c9a-9939-c8b8e248a05e, desactivada al terminar.

| Ejecucion | ID | Resultado |
| --- | --- | --- |
| Nivel 0 manual | 94f0ff2a-4502-446d-bc16-25e2157679b4 | EXITOSO, verificacion posterior correcta |
| VALIDATE manual de ese respaldo | 9fa9de4d-451a-4027-8a5e-0002791031ec | EXITOSO, vinculo al respaldo correcto |
| Nivel 0 por Quartz, 17:42 local | 10d62fb0-4e8d-4381-9be9-a3a32ab6fe8a | EXITOSO, verificado, aproximadamente 34 segundos |

El test de navegador tuvo primero un selector ambiguo de Nombre y despues un
timeout al transicionar del cierre movil a la navegacion. Se acoto el selector
al formulario de estrategia y se espero explicitamente su cierre con Cancelar.
La repeticion completa paso; una comprobacion adicional del boton X movil tambien
paso. No se modifico el codigo de produccion durante esta regresion.

## Repetir las pruebas

En la terminal de la aplicacion, detener con Ctrl+C y ejecutar desde la raiz:

```powershell
java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"
```

Despues de confirmar el nuevo contrato de preview y que no hay tareas activas:

```powershell
./scripts/lab/prueba-flujo-local.ps1 -Method LEVEL0
```

Esta prueba crea una estrategia QA pequena, verifica un respaldo manual y otro
programado, comprueba el bloqueo antes de aprobar, el preview invalido y la lista
de datafiles. Desactiva su propio horario al terminar y no borra respaldos.
Las 23 comprobaciones pasaron en la ejecucion identificada arriba.

`scripts/lab/prueba-ui.cjs` usa Playwright (resoluble por NODE_PATH): valida errores
semanticos/sintacticos, respuesta de preview retrasada, formulario movil e historial.
No guarda estrategias ni ejecuta RMAN. La respuesta retrasada es una condicion
controlada de red en el navegador, no una modificacion del servidor.

## Limites que permanecen

- El espacio final de componentes no se puede estimar con los datos disponibles;
  se advierte explicitamente, no se promete que el respaldo quepa.
- Una copia nivel 0 del historial no garantiza que los archivos sigan presentes
  ni que todo el redo necesario este disponible. La lista nueva es del diagnostico
  previo: no certifica encarnaciones ni reutilizacion de IDs. Se exige una copia
  que cubra todo el alcance y una estrategia registrada de cobertura coincidente.
- Los registros antiguos conservan su verificacion original, pero sin lista de
  datafiles no silencian la nueva advertencia de nivel 0.
- Ventana de duracion no es franja horaria ni timeout. Su interpretacion debe
  presentarse como decision de diseno del grupo, no como texto explicito del PDF.
- No se repitieron las 51 comprobaciones ampliadas ni las dos recuperaciones QA
  durante esta correccion. El generador de instrucciones BACKUP no cambio.
- Falta ensayo y especificacion final de la exposicion; no se inventaron duracion,
  participantes, diapositivas ni un formato solicitado por el profesor.
