# Revision independiente frente al enunciado
Fecha: 28 de septiembre de 2026.

## Hallazgos prioritarios

P1 significa corregir antes de confiar en el control preventivo. P2 significa corregir para completar correctamente el comportamiento y la entrega. Son prioridades de revision, no una calificacion del profesor.

### H1. [P1] La verificacion no identifica los conjuntos generados por cada ejecucion
**Referencia:** [RmanScript.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/RmanScript.java:41>). Enunciado: 10, 14.7, 14.9 y 14.10.

CROSSCHECK se restringe por TAG, pero los RESTORE ... VALIDATE posteriores no se restringen por ejecucion, conjunto o pieza. El TAG, ademas, es comun a las ejecuciones de la estrategia. El resultado permite afirmar que RMAN encontro elementos para validar una restauracion; no que leyo todas las piezas del respaldo recien generado.

No es solo una posibilidad teorica. En [registro real de verificacion](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/runtime/executions/9a53e209-06c2-4e53-8ab0-c1b82668e179/verify.log:46>), la validacion de archived logs escanea los archivos originales. Para control file y SPFILE lee el autobackup en dbhomeFree/dbs, en lugar de las piezas explicitas guardadas en /opt/oracle/backup. La pieza de datos si se lee. Por tanto, el registro no prueba la lectura de todos los archivos explicitamente producidos por la estrategia.

Oracle distingue entre RESTORE VALIDATE, que permite a RMAN seleccionar los respaldos, y VALIDATE BACKUPSET, que permite indicar los conjuntos concretos: [referencia oficial VALIDATE](https://docs.oracle.com/en/database/oracle/oracle-database/21/rcmrf/VALIDATE.html).

**Correccion recomendada:** registrar las claves de los backup sets y su relacion con cada ejecucion; validar esos conjuntos y conservar el resultado por conjunto. Mantener por separado la comprobacion de que existe una cadena util para recuperar. Limitar solo por TAG de estrategia no identifica una ejecucion.

**Aceptacion:** con dos respaldos del mismo alcance, una validacion del segundo debe comprobar sus conjuntos y no quedar aprobada solo porque el primero u otras copias permiten restaurar. Probar en un laboratorio aislado.

### H2. [P1] Una configuracion con un error conocido puede ejecutarse
**Referencia:** [BackupService.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/BackupService.java:136>) y [RmanScript.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/RmanScript.java:117>). Enunciado: 8 y 14.4.

La aprobacion consulta el ultimo diagnostico guardado; no tener diagnostico o no poder conectar solo produce advertencias. Al ejecutar se comprueba la aprobacion, pero no se vuelve a invocar la validacion semantica ni a comprobar el estado actual de Oracle.

**Reproduccion confirmada:** aprobar sin diagnostico, registrar despues un diagnostico con DESTINO_INEXISTENTE y ejecutar. El servicio despacho el script al ejecutor simulado aun cuando RmanScript.check devolvia ERROR. El resultado EXITOSO de esta prueba procede exclusivamente del doble de prueba: no representa un respaldo real.

**Impacto:** puede programarse o lanzarse una operacion que el propio sistema ya sabe que es invalida. El refresco de la interfaz cada cinco segundos no actualiza el diagnostico de Oracle.

**Correccion recomendada:** exigir una comprobacion suficiente antes de aprobar y volver a validar antes de despachar. Definir vigencia del diagnostico y distinguir errores impeditivos de advertencias aceptadas conscientemente. No impedir por principio toda operacion en NOARCHIVELOG: un respaldo consistente con la base en MOUNT es otro caso.

### H3. [P1] El intervalo anunciado puede convertirse en una ejecucion diaria
**Referencia:** [Schedules.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Schedules.java:16>). Enunciado: 7 y 9.

El campo de horas cron se genera como horaInicial/intervalo. Ese contador se reinicia cada dia, no representa un intervalo continuo entre ejecuciones.

**Reproduccion confirmada:** todos los dias, cada 5 horas desde las 20:00. Las ocurrencias calculadas son 20:00, 20:00 y 20:00 en dias sucesivos: separacion de 24 horas. No son 20:00, 01:00, 06:00, etc. La misma expresion alimenta Quartz, asi que afecta la ejecucion real y no solamente la vista previa.

**Correccion recomendada:** definir explicitamente si el intervalo es continuo o una repeticion dentro de una franja diaria. Para el comportamiento anunciado actualmente, usar un mecanismo de intervalos con ancla de inicio y una politica clara para los dias permitidos.

### H4. [P1] Un timeout de verificacion libera la base sin confirmar el fin de RMAN
**Referencia:** [BackupService.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/BackupService.java:206>) y [BackupService.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/BackupService.java:169>). Enunciado: control preventivo; tambien contradice RNF02 del proyecto.

El timeout del respaldo inicial produce INCIERTO y conserva el bloqueo. En cambio, el timeout de la verificacion posterior entra en el caso generico FALLIDO. El bloque final libera la base.

**Reproduccion confirmada:** respaldo simulado correcto seguido por una verificacion con timedOut=true. Resultado FALLIDO y mapa de bases activas vacio. Terminar el cliente docker exec no confirma que el proceso dentro del contenedor haya terminado.

**Correccion recomendada:** conservar INCIERTO y el bloqueo tambien en esta fase; guardar la evidencia parcial del respaldo. Liberar unicamente al confirmar la terminacion o mediante la decision explicita prevista para el administrador.

### H5. [P2] La proxima ejecucion omite horarios del mismo dia
**Referencia:** [Schedules.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Schedules.java:34>). Enunciado: 7, 9 y claridad de la programacion.

El limite global de resultados se consume con la primera expresion cron antes de considerar las siguientes.

**Reproduccion confirmada:** el 28/09/2026 a las 10:00, con horarios 08:00, 12:00 y 18:00, next devuelve el 29/09 a las 08:00. Debe devolver el 28/09 a las 12:00. Tambien afecta la vista previa de varias ocurrencias. Los disparadores diarios independientes de Quartz no necesariamente comparten este error: no confundir la fecha mostrada incorrecta con la ausencia de todas las ejecuciones reales.

**Correccion recomendada:** combinar ordenadamente las ocurrencias de todas las expresiones y aplicar el limite despues, o usar una cola de proximos disparos.

### H6. [P2] El estado de verificacion puede ocultar comprobaciones pendientes o fallidas
**Referencia:** [Alerts.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Alerts.java:115>) y [app.js](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/resources/web/app.js:241>). Enunciado: 11 y 14.7.

**Caso confirmado:** un respaldo correcto sin verificar genera SIN_VERIFICAR. Activar la opcion de verificacion para futuras ejecuciones elimina esa recomendacion inmediatamente, sin haber verificado el respaldo anterior.

Ademas, el monitor busca validaciones exitosas historicas sin dar precedencia a una validacion fallida posterior. La interfaz puede conservar el paso Verificada como realizado, y la regla EJECUCION_FALLIDA solo considera operaciones BACKUP. El fallo sigue en el historial, pero no se refleja adecuadamente en el control preventivo.

**Correccion recomendada:** basar el estado en evidencia de la ejecucion concreta, no en la opcion actual ni en cualquier exito anterior. Una validacion fallida posterior debe generar su propia alerta y revisar el estado mostrado.

### H7. [P2] Los controles y el historial solo consultan las ultimas 500 ejecuciones
**Referencia:** [Catalog.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Catalog.java:47>) y [BackupService.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/BackupService.java:28>). Enunciado: 10 y 11.

El limite es global, no por estrategia. La misma consulta sirve para la pantalla, las alertas y la reconstruccion de operaciones pendientes al arrancar.

**Impacto por inspeccion del codigo:** despues de suficiente actividad, una estrategia poco frecuente puede perder su ultimo respaldo del conjunto analizado; una ejecucion antigua INCIERTO puede dejar de reconstruir su bloqueo al reiniciar. Los registros no se borran de H2, pero no hay paginacion para recorrerlos desde la interfaz.

**Correccion recomendada:** separar historial paginado de consultas operativas: todas las operaciones pendientes, ultimo respaldo por estrategia/version, ultimas verificaciones y ejecuciones del periodo requerido. No reproduje 501 ejecuciones contra el catalogo del usuario.

### H8. [P2] Cambiar el alcance conserva evidencia de la configuracion anterior como si fuera actual
**Referencia:** [Alerts.java](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Alerts.java:72>) y [app.js](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/resources/web/app.js:241>). Enunciado: 9, 10 y 11.

Las alertas y el ciclo visual asocian evidencia solamente por strategyId. Si una estrategia antes respaldaba un tablespace y ahora respalda otro, el exito y la verificacion previos siguen alimentando sus indicadores. La nueva aprobacion no cambia esa asociacion.

**Impacto por inspeccion:** despues de aprobar el nuevo alcance, puede mostrarse el ciclo como completado basandose en respaldos que no cubren ese alcance. Existe scriptHash en las ejecuciones, pero no se usa para esta decision. Lo mismo merece revision al cambiar de base.

**Correccion recomendada:** conservar una version de la estrategia y su alcance en cada ejecucion; evaluar cobertura por version y base. No eliminar la evidencia anterior: presentarla como historial de una configuracion previa.

## Dictamen general

El proyecto esta bien orientado y tiene implementada gran parte de la estructura que pide el profesor. No es solamente un lanzador de scripts: hay construccion de estrategias, aprobacion, automatizacion, evidencia y monitor preventivo.

No lo calificaria como cumplimiento completo todavia. Las brechas principales estan en la confiabilidad de la programacion, la validacion antes de ejecutar y el significado de una verificacion exitosa. Se puede presentar como avance sustancial, explicando estas limitaciones; no conviene afirmar que todos los controles ya estan cerrados.

No asigno un porcentaje ni una nota porque el documento no proporciona ponderaciones de evaluacion.

## Alcance de la revision

- Fuente normativa: enunciado oficial de 13 paginas; no las suposiciones iniciales de requisitos-proyecto.md.
- Inspeccion de modelos, generacion RMAN, programador, servicio, catalogo, alertas, rutas HTTP, interfaz, pruebas y documentos.
- Suite del proyecto: **18 pruebas, 0 fallos, 0 errores**. Esto no demuestra que todos los requisitos esten cubiertos.
- Pruebas aisladas adicionales con H2 temporal y un ejecutor falso: H2, H3, H4, H5 y el primer caso de H6 quedaron reproducidos.
- Revision de registros historicos reales de respaldo, verificacion y recuperacion. No se repitio una recuperacion destructiva ni un respaldo real.
- La conexion a http://127.0.0.1:8787/api/state fue rechazada durante la revision. No se verifico una sesion completa de la interfaz funcionando ni el estado actual de Oracle.
- No se modifico codigo funcional ni el catalogo del usuario. Este informe es nuevo; las comprobaciones quedaron en la carpeta temporal ignorada por Git.

## Matriz de cumplimiento

Cumplido significa que existe implementacion o documentacion verificable para ese punto, con el alcance indicado. No significa prueba exhaustiva de todas sus variantes en Oracle.

| Seccion | Requisito | Estado | Evidencia y observacion |
| --- | --- | --- | --- |
| 1-3 | Gestion de estrategias y aporte al control preventivo | Parcial | La arquitectura y el monitor responden al enfoque; H1-H8 afectan su confiabilidad |
| 4 | Que, como y cuando diferenciados | Cumplido | Strategy, constructor por pasos y generador |
| 4.1 | Base, tablespaces, datafiles, control file, SPFILE, archived logs | Cumplido en implementacion | Opciones independientes y comandos correspondientes; no se repitieron todas las variantes contra Oracle |
| 4.1 | Prioridad y criterios del grupo | Cumplido | Alta/media/baja; criterios de impacto y umbrales 24/72/168 h documentados |
| 5 | FULL, nivel 0, nivel 1 diferencial y acumulativo | Cumplido en implementacion | Generacion diferenciada y pruebas de scripts |
| 5 | Comparacion de los siete criterios minimos | Parcial | Hay cinco criterios explicitamente comparados; faltan disponibilidad y proteccion por modalidad |
| 6 | Identificacion y explicacion ARCHIVELOG/NOARCHIVELOG | Cumplido con limite operativo | Diagnostico, documentacion y mensajes; el diagnostico es manual y puede quedar desactualizado |
| 6 | Informacion, advertencia y recomendacion diferenciadas | Cumplido | Niveles y acciones separados |
| 6 | No cambiar automaticamente el modo desde la aplicacion | Cumplido en el flujo revisado | El administrador aplica recomendaciones; la preparacion del laboratorio debe distinguirse de la app |
| 7 | Nombre, descripcion, base, responsable, prioridad, estado | Cumplido | Campos disponibles; ausencias de descripcion/responsable se advierten |
| 7 | Tipo, nivel, compresion y opciones | Cumplido | Generador y constructor |
| 7 | Fecha, hora, frecuencia, dias, intervalo y ventana | Parcial | Campos presentes, pero H3 y H5; la ventana es duracion maxima advertida, no una franja de inicio/fin |
| 7 | Destino, dispositivo y espacio | Cumplido con alcance DISK | Ruta configurable, dispositivo DISK identificado, medicion df; el enunciado no exige cinta ni todos los dispositivos |
| 8 | Configurar, validar, construir, mostrar, aprobar, programar | Parcial | Flujo y huella presentes; la validacion puede omitirse en la practica, H2 |
| 8 | Documentar la transformacion a RMAN | Cumplido | Tabla en analisis-y-diseno.md |
| 9 | Ejecucion automatica y trazabilidad | Parcial | Quartz, ids, ocurrencias y scripts; H3/H5/H8, sin prueba nueva de disparo real contra Oracle |
| 10 | Campos de evidencia y tres estados minimos | Cumplido en implementacion | Execution, scripts y logs por ejecucion; tambien existe INCIERTO y OMITIDO |
| 10 | Consulta y conservacion del historial | Parcial | Persiste en H2; consulta limitada sin paginacion, H7 |
| 11 | Alertas preventivas | Parcial | Las nueve condiciones de ejemplo tienen reglas, pero algunas dependen de datos antiguos y H6-H8 |
| 12 | Arquitectura conceptual | Cumplido | Modulos Java, RMAN, Oracle, H2, interfaz y diagramas; el esquema del PDF es una referencia |
| 13.1 | Documento de analisis | Parcial | Contenido sustancial presente; completar comparacion y precisiones tecnicas |
| 13.2 | Diseno de aplicacion | Cumplido como borrador | Arquitectura, modulos, modelo, diagramas, interfaz y flujo documentados |
| 13.3 | Prototipo funcional | Parcial | Operaciones implementadas y evidencia historica; permanecen errores concretos y falta demostracion actual de extremo a extremo |
| 13.4 | Evidencias exigidas | Parcialmente verificadas | Exito, error, scripts y recuperacion tienen registros locales; consulta visual y aplicacion de recomendacion requieren paquete demostrable |
| 13.5 | Presentacion | Pendiente de verificar | El software no sustituye la demostracion oral; no se evaluo una presentacion |
| 14.1-3 | Entorno de pruebas y recuperacion controlada | Evidencia favorable | Laboratorio y prueba documentada; no se hicieron operaciones destructivas en esta revision |
| 14.4 | Validar antes de generar o ejecutar | No cumplido de forma robusta | H2 |
| 14.5-6 | Revision previa y recomendacion no automatica | Cumplido | Vista previa, aprobacion y acciones explicitas |
| 14.7-9 | No inferir exito; existencia y validacion de respaldos | Parcial | stat y comandos RMAN existentes; H1/H4/H6 |
| 14.10 | Considerar recuperacion posterior | Cumplido como evidencia puntual | Prueba de recuperacion de un datafile; no demuestra todas las estrategias |
| 15-16 | Cadena completa hasta control preventivo | Parcial | Diseño completo, pero los controles deben corregirse y demostrarse de nuevo |

## Detalle de las alertas del enunciado

| Condicion | Lo que hace hoy | Limite relevante |
| --- | --- | --- |
| Sin programacion | Detecta times vacio | Correcto como caso basico |
| Inactiva | Advierte enabled=false | Correcto como caso basico |
| Programado no ejecutado | Compara ocurrencias y registros | Usa horario actual hacia atras; cambios de horario sin nueva version pueden producir falsas omisiones |
| Ejecucion fallida | Advierte ultimo BACKUP fallido | No da el mismo tratamiento a VALIDATE fallido |
| Falta de espacio | Compara espacio guardado con tamano de datafiles | No refresca solo; no estima archived logs/control/SPFILE ni el crecimiento entre diagnostico y respaldo |
| Archived logs ausentes | Considera modo, contador y estrategias configuradas | No demuestra continuidad de secuencias necesarias para recuperar |
| NOARCHIVELOG | Advertencia especifica | Estado del ultimo diagnostico, no necesariamente actual |
| Sin respaldo reciente | Compara ultimo exito con umbral | H7/H8; un exito con advertencias tambien cuenta |
| Configuracion incompleta | Responsable, descripcion y objetos/destino | La deteccion no asegura bloqueo antes de ejecutar, H2 |

Adicional: SIN_NIVEL0 desaparece al existir una estrategia nivel 0 activa con alcance compatible, aunque aun no este aprobada, programada ni ejecutada. Es razonable como aviso de configuracion, pero no demuestra que ya exista una base incremental util.

## Documentacion que conviene corregir

1. **Comparacion incompleta:** la seccion 5 exige necesidades de disponibilidad y de proteccion de la informacion, ademas de los cinco criterios ya comparados. Agregar por modalidad escenarios, ventajas, limitaciones y consecuencias para recuperar; no basta una mencion general al inicio.
2. **FULL no significa una sola pieza:** [analisis-y-diseno.md](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/docs/analisis-y-diseno.md:117>) dice "Baja: una pieza". Un backup set puede repartirse y una operacion puede producir varios conjuntos; la simplificacion confunde tipo de respaldo con estructura fisica.
3. **Consistencia del respaldo en linea:** matizar que volver al momento del respaldo no siempre consiste en restaurar datos y nada mas. Puede requerirse redo para hacer consistente una copia tomada con la base abierta. Separar restaurar, recuperar y abrir.
4. **Antiguedad no equivale por si sola al RPO:** los umbrales elegidos son validos como politica del grupo, pero no demuestran la maxima perdida recuperable de datos. Esa conclusion depende de la cadena de respaldos y redo disponible.
5. **La aprobacion protege el script, no toda la configuracion:** cambiar solo horas, dias o responsable no cambia el script y no invalida la aprobacion. Esto contradice la afirmacion amplia de RF08 y de la revision anterior. El enunciado exige aprobar el script; decidir y documentar si tambien se versiona/aprueba la politica completa.
6. **No presentar la revision del 23/09 como auditoria definitiva:** afirma que las brechas de validacion ya estan resueltas. H2 demuestra que esa conclusion es demasiado amplia.
7. **Aclarar el modelo logico:** el diagrama marca relaciones FK, pero H2 almacena JSON y no implementa esas claves foraneas fisicas. Es valido como modelo conceptual si se explica.
8. **Ventana de respaldo:** la implementacion trata el valor como limite de duracion y avisa si se excede; preguntar si el profesor espera una franja permitida con hora de apertura/cierre. El PDF no detalla esa semantica.

Como referencia tecnica sobre estructura de copias y recuperacion, consultar [conceptos de respaldos de Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/21/bradv/rman-backup-concepts.html) y la [referencia RESTORE](https://docs.oracle.com/en/database/oracle/oracle-database/21/rcmrf/RESTORE.html). Estas fuentes sustentan las precisiones de RMAN, no agregan requisitos al enunciado.

## Evidencia existente y pendiente de presentar

- **Respaldo real:** hay scripts, salida RMAN y piezas locales para EST001. Un ejemplo es la ejecucion 9a53e209-06c2-4e53-8ab0-c1b82668e179. Esto demuestra actividad historica, no el estado actual del contenedor.
- **Error real controlado:** los registros 767e07bb-0f77-416a-9ae1-778a8b315c11 y 1c3cacb3-5db8-4eb1-84e1-5c33690cc445 contienen ORA-19504 por destino inexistente.
- **Recuperacion:** [prueba-recuperacion.md](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/docs/evidencias/prueba-recuperacion.md>) y las carpetas locales de pruebas conservan evidencia de RESTORE/RECOVER. Es un buen aporte, limitado al escenario ensayado.
- **Recomendacion aplicada:** la revision anterior describe la incorporacion de archived logs en EST003 y su bitacora. En esta revision no se reejecuto esa accion ni se verifico su evento en el catalogo real. Incluir captura o exportacion del evento y scripts anterior/posterior.
- **Consulta del historial y advertencia:** preparar capturas o una secuencia reproducible para la presentacion, con identificadores que coincidan con scripts y registros.
- **Entrega portable:** no depender solo de archivos en runtime ignorados por Git. Preparar un paquete pequeno con los registros relevantes, sin contrasenas, datos sensibles ni necesidad de subir todos los respaldos binarios.

## Pruebas recomendadas antes de cerrar

1. Intervalos que crucen medianoche: 5 h desde 20:00, y dias restringidos.
2. Varios horarios: antes, entre y despues de las horas configuradas; comparar vista previa y disparos.
3. Aprobacion sin diagnostico, diagnostico vencido y destino que deja de existir despues de aprobar.
4. Timeout en respaldo y en verificacion; el bloqueo debe conservarse en ambos.
5. Dos respaldos del mismo alcance: verificar las piezas de la ejecucion elegida.
6. Activar verificacion no debe verificar retroactivamente; un fallo posterior debe reflejarse.
7. Editar alcance/base y volver a aprobar: la evidencia anterior no debe certificar la nueva estrategia.
8. Mas de 500 ejecuciones distribuidas entre estrategias y una operacion incierta antigua.
9. Demostracion de extremo a extremo contra Oracle de laboratorio, con una ejecucion automatica observable.
10. Paquete de las seis evidencias de la seccion 13.4 y ensayo de explicacion que/como/cuando/script/ejecucion/evidencia.

## Orden de trabajo sugerido

Primero H2 y H3, para ejecutar configuraciones validas en el horario prometido. Despues H1 y H4, para que la verificacion y los bloqueos sean confiables. A continuacion H5-H8 y sus pruebas. Finalmente, completar el analisis comparativo, reunir evidencias y ensayar la demostracion.

No hay fundamento en el PDF para exigir como obligatorios correo electronico, nube, autenticacion multiusuario, cintas, retencion automatica, una interfaz de recuperacion destructiva o ejecucion con la aplicacion cerrada. Son posibles ampliaciones, no incumplimientos automaticos. Java, Docker y Quartz son compatibles con el alcance planteado.

