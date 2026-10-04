# Revision de cumplimiento y preparacion del avance

Fecha: 4 de octubre de 2026. Avance previsto por el usuario: 5 de octubre.

> Revision historica anterior a las correcciones de esta misma fecha. Los
> hallazgos y la matriz siguientes describen el estado revisado, no los pendientes
> actuales. Consultar [correcciones y matriz actualizada, puntos 1-16](correcciones-revision-enunciado-2026-10-04.md).

## Hallazgos prioritarios

### 1. P2: el aviso de espacio no cubre estrategias de solo componentes

Enunciado: secciones 7 y 11, paginas 6 y 8.

[RmanScript.java, estimacion](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/RmanScript.java:181>)
solo suma datafiles. Para COMPONENTS devuelve cero, aunque archived logs, control
file y SPFILE ocupan espacio. La comparacion de la linea 174 no advierte cuando
el espacio libre es tambien cero.

Reproduccion aislada contra el JAR actual, sin escribir en Oracle ni llenar el
disco: diagnostico con 0 KB libres, estrategia COMPONENTS con sus tres opciones.
Resultado: estimatedBytes=0 y ninguna observacion ESPACIO_INSUFICIENTE. Con el
mismo diagnostico y un tablespace de 32 MiB, la advertencia si aparece.

Impacto: hay un caso de falta de capacidad que el control preventivo no detecta.
No significa que el respaldo se marque exitoso si RMAN falla; ese es otro control.

Recomendacion: advertir independientemente ante espacio agotado; estimar los
componentes o expresar explicitamente que su volumen es desconocido. Agregar
pruebas para COMPONENTS con cero espacio y estimacion no disponible. No inventar
un tamano preciso a partir de la suma de datafiles.

Evidencia de esta revision: tmp/review/ComplianceProbe.java y
tmp/review/compliance-space.json. La reproduccion no integra las 41 pruebas
automatizadas anteriores ni implica que aquellas hayan fallado.

### 2. P2: la generacion del borrador antecede a la validacion semantica

Enunciado: secciones 8 y 14.4, paginas 6-7 y 11.

[BackupService.java, preview](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/BackupService.java:50>)
construye el script antes de consultar RmanScript.check. Se comprobo con la
estrategia QA_INVALID_20261004161551: la vista previa devuelve tanto el ERROR
DATAFILE_INEXISTENTE como un BACKUP DATAFILE 65533 generado.

Los campos ya pasan validaciones sintacticas en Models.Strategy. Ademas, la
aprobacion y la ejecucion exigen diagnostico actualizado y bloquean errores.
Por ello no se ha demostrado una ejecucion insegura por este comportamiento.

La observacion es de alineacion con el flujo literal del enunciado: no afirmar
que nunca se genera un script si la configuracion es invalida. Recomendacion:
validar semanticamente antes de producir el script, o distinguir un borrador
invalido de un script validado y confirmar con el profesor esa interpretacion.

### 3. Cobertura acotada de las alertas de antiguedad y cadena incremental

Enunciado: seccion 11, pagina 8; secciones 5 y 14.10.

[Alerts.java, linea 90](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Alerts.java:90>)
solo evalua SIN_RESPALDO_RECIENTE dentro de estrategias activas, aprobadas y con
horarios. Las estrategias manuales, inactivas o pendientes de aprobacion reciben
otras advertencias, pero no esa comprobacion de antiguedad. Es una politica
limitada: documentarla o separar la evaluacion de frescura del planificador.

[Alerts.java, linea 118](<C:/Users/Adrian Fernandez/Documents/ChatGPT/Administracion de Bases de datos/src/main/java/edu/respaldos/Alerts.java:118>)
considera la existencia de una estrategia nivel 0 activa y de alcance compatible
para el aviso SIN_NIVEL0. No exige que esa estrategia ya haya producido una copia
valida. La ausencia del aviso no acredita una cadena recuperable. La prueba real
del 04/10 si demostro dos cadenas concretas, de forma independiente de esa regla.

### 4. Aspectos que deben aclararse para la entrega

- La ventana de respaldo es una duracion maxima advertida, no una franja de
  inicio/fin ni un mecanismo que interrumpa RMAN. El enunciado pide ventana,
  pero no define su semantica: consultarlo, no declararlo incumplimiento seguro.
- Hay tablas y diagramas que todavia describen toda la programacion como cron.
  En realidad hay CronTrigger para horas diarias/semanales y SimpleTrigger para
  intervalos continuos. Schedules.cron existe, pero no representa ambos casos.
- La documentacion menciona RmanScript.validate de forma general; la evidencia
  certificada de cada ejecucion usa validateSets y VALIDATE BACKUPSET.
- El codigo, registros y diagramas no equivalen a tener la presentacion y el
  paquete final ya preparados. No se ha verificado un archivo de diapositivas,
  un formato institucional final ni el reparto de la exposicion entre miembros.

## Dictamen

El nucleo funcional exigido esta implementado y cuenta con evidencia real:
configurar, revisar, aprobar, programar, ejecutar, verificar, conservar historial
y mostrar controles preventivos. Es un avance demostrable, no solamente un
generador de scripts ni una simulacion.

No corresponde afirmar cumplimiento total sin reservas: queda la brecha de
espacio, precisar la generacion de borradores y el alcance de las alertas, y
preparar la entrega. No se asigna un porcentaje ni una calificacion ficticia:
el PDF no proporciona ponderaciones para calcularlos.

Las pruebas anteriores siguen siendo validas para sus casos: 41 automatizadas,
51 comprobaciones reales ampliadas y 10 de recomendaciones aprobadas. Pasar esos
casos no demuestra ausencia de todos los errores posibles; esta revision agrega
un caso limite que no estaba cubierto.

## Documento y metodo

Se leyeron las 13 paginas del PDF proporcionado en Downloads, terminado en (2).
Su SHA-256 coincide con docs/enunciado-proyecto.pdf:
6514FCE3C618324274FD5B7F5EBE8CCA3CE8D7138D55173A778FAC37F896DBB7.
No es una version diferente del enunciado.

Se contrastaron el texto completo, las paginas visuales de arquitectura y
entregables, el codigo actual, los documentos, el estado de la API y los
registros conservados. En esta revision no se modifico el codigo de produccion,
no se aprobaron estrategias y no se ejecutaron nuevos respaldos ni recuperaciones.

Estados utilizados: cumplido comprobado, implementado con limites, parcial o
pendiente. Una limitacion de produccion no se convierte automaticamente en un
requisito academico no satisfecho.

## Matriz de cumplimiento

| Requisito del PDF | Estado | Sustento y limite |
| --- | --- | --- |
| 1-3. Gestion de estrategias y control preventivo | Cumplido con observaciones | Modelo, planificador, evidencia, alertas y relacion con disponibilidad/integridad; mejorar cobertura indicada arriba |
| 4. Base completa, tablespaces, datafiles y componentes | Cumplido comprobado | Cuatro alcances y casillas independientes; ocho respaldos reales del 04/10 |
| 4. Prioridades y criterios del grupo | Cumplido documentado | Alta/media/baja; criterios de impacto y umbrales 24/72/168 h; no son valores impuestos por el profesor ni garantia de RPO |
| 5. FULL, nivel 0, nivel 1 diferencial y acumulativo | Cumplido comprobado | Constructor y generador; respaldo FULL, cadena L0/L1 y dos recuperaciones reales |
| 5. Comparacion con los siete criterios pedidos | Cumplido documentado | Tabla de analisis-y-diseno: espacio, tiempo, frecuencia, cambios, recuperacion, disponibilidad y proteccion |
| 6. ARCHIVELOG y NOARCHIVELOG | Implementado con evidencia de distinto tipo | ARCHIVELOG probado en Oracle real; NOARCHIVELOG documentado y cubierto en pruebas aisladas/simuladas, no se cambio el modo real |
| 6. Informacion, advertencia, recomendacion; decision humana | Cumplido comprobado | Niveles diferenciados; aplicar recomendacion cambia script e invalida aprobacion; no cambia automaticamente el modo de archivado |
| 7. Informacion general de la estrategia | Cumplido en interfaz y modelo | Nombre, descripcion, base, responsable, prioridad y estado |
| 7. Que y como, nivel y compresion | Cumplido comprobado | Campos y scripts; se probaron copias comprimidas y diferencial sin compresion |
| 7. Fecha, hora, frecuencia, dias e intervalo | Cumplido con evidencia | Quartz real el 28/09; calculos de horarios e intervalos en pruebas automatizadas |
| 7. Ventana | Implementado; aclarar interpretacion | Limite de duracion advertido, no franja horaria |
| 7. Destino, dispositivo y espacio | Parcial en prevencion | Ruta, DISK y espacio visibles; estimacion incompleta para componentes |
| 8. Transformacion, visualizacion y aprobacion | Cumplido con reserva de orden | Se genera y muestra el script; se aprueba su huella; el borrador antecede a la validacion semantica |
| 9. Automatizacion y trazabilidad | Cumplido comprobado | Quartz y relacion estrategia/horario/script/ejecucion; disparo real 28/09 19:34; requiere aplicacion y Oracle en marcha |
| 10. Campos de evidencia y tres estados | Cumplido en implementacion y pruebas | Execution, scripts, tiempos, resultado, errores, destino y logs; EXITOSO, CON_ADVERTENCIAS, FALLIDO, ademas de INCIERTO/OMITIDO |
| 10. Consulta y conservacion del historial | Cumplido para el prototipo | H2 persiste y la interfaz consulta; sin limite global de 500; paginacion pendiente para grandes volumenes |
| 11. Condiciones preventivas | Implementado con brechas | Reglas de las nueve condiciones; cobertura de espacio/antiguedad/cadena limitada |
| 12. Arquitectura conceptual | Cumplido | Navegador, Java, Quartz, H2, Docker, RMAN y Oracle; el esquema del PDF es referencia, no tecnologia obligatoria |
| 13.1. Documento de analisis | Contenido presente; entrega por preparar | analisis-y-diseno.md contiene los nueve temas; precisar textos y adecuar al formato pedido |
| 13.2. Diseno de aplicacion | Contenido presente; entrega por preparar | Arquitectura, modulos, modelo conceptual, diagramas, interfaz y flujo; ajustar etiquetas del planificador |
| 13.3. Prototipo funcional | Cumplido con reservas ya descritas | Registro de base implementado; construccion, aprobacion, automatizacion, respaldo y resultado observados |
| 13.4. Seis evidencias | Disponibles; paquete pendiente | Hay registros para las seis; identificar fechas y distinguir error historico de rechazo preventivo actual |
| 13.5. Presentacion | Pendiente de preparar/ensayar | Esperando los puntos especificos que el usuario ofrecio compartir |
| 14.1-3. Entorno y recuperacion controlada | Cumplido comprobado | rman-lab; recuperaciones limitadas a QA; cinco pedidos originales sin cambios |
| 14.4. Validacion antes de generar o ejecutar | Parcial en lectura literal | Hay validacion sintactica y bloqueo antes de ejecutar; observar generacion del borrador |
| 14.5-6. Revision y recomendacion no automatica | Cumplido comprobado | Vista previa, aprobacion, acciones explicitas y bitacora |
| 14.7-9. Exito no supuesto, piezas y verificacion RMAN | Cumplido en casos ensayados | Comprobacion de archivos, claves de conjuntos y lectura; fallos de evidencia cubiertos de forma aislada |
| 14.10. Considerar recuperacion posterior | Cumplido con alcance probado | Recuperacion diferencial y acumulativa de QA; no perdida total de la instancia |
| 15-16. Cadena completa y explicacion del riesgo | Implementado; demostrar en exposicion | Hay trazabilidad de principio a fin; el grupo debe explicar el aporte, no solo mostrar botones |

## Las seis evidencias para presentar

| Evidencia exigida, pagina 11 | Material disponible | Como presentarlo sin confundir |
| --- | --- | --- |
| Ejecucion exitosa | FULL 36fb774f-2022-4385-ae8e-42af8432af0d o L0 b72c7b11-8402-4c37-b0bf-274733dc0bec, del 04/10 | Abrir registro, piezas y verificacion de esa misma ejecucion |
| Ejecucion fallida o error controlado | EST900, 1c3cacb3-5db8-4eb1-84e1-5c33690cc445, del 23/09 local; ORA-19504 | Es evidencia historica real de una version anterior; el codigo actual bloquea ese destino antes de despachar RMAN |
| Consulta de historial | Vista Ejecuciones, comprobada con Playwright el 04/10 | Mostrar un registro exacto y sus detalles; no solo un contador |
| Generacion del script | Constructor/vista previa y script.rman de cada ejecucion | Relacionar alcance, metodo y destino con las instrucciones; aclarar que un borrador puede tener errores |
| Condicion de advertencia | Estrategias QA inactivas o sin programacion, observadas en la API | Esas advertencias son deliberadas; no significan que las copias exitosas hayan fallado |
| Aplicacion de recomendacion | QA_RECOMENDACION_20261004162948, diez comprobaciones y dos eventos | Mostrar antes/despues del script, aprobacion invalidada y RECOMENDACION_APLICADA |

Una solicitud rechazada por la validacion no es una ejecucion RMAN fallida.
Puede servir como demostracion de prevencion, pero no debe rotularse como si
RMAN hubiese corrido. No hace falta provocar perdida real de datos para exponer.

La ejecucion automatica observada tiene ID 61ac047c-a2ec-4367-9cec-24ad0eaaa00a:
28/09, 19:34 local, 35 s aproximadamente. El 04/10 no se repitio ese disparo.

## Base para explicar el avance

Esta secuencia sale de la seccion 13.5; es provisional hasta recibir los puntos
especificos del avance, su duracion y las instrucciones adicionales del profesor.

1. Necesidad: proteger pedidos del laboratorio y reducir olvidos y copias no
   comprobadas. Presentar disponibilidad e integridad como riesgos distintos.
2. Que: elegir la base y FREEPDB1:LAB_DATOS; explicar los componentes adicionales.
3. Como: elegir FULL o una cadena L0/L1 y justificar la eleccion, sin confundir
   FULL con nivel 0 ni con una sola pieza.
4. Cuando: mostrar fecha, horarios e intervalo; explicar Quartz, aprobacion y
   dependencia de que la aplicacion permanezca encendida.
5. Script: identificar ALLOCATE CHANNEL, BACKUP, TAG, FORMAT y componentes.
   Mostrar la validacion y la aprobacion antes de ejecutar.
6. Ejecucion y evidencia: abrir una copia real, las piezas y VALIDATE BACKUPSET;
   mostrar tambien la evidencia del disparo programado.
7. Prevencion: explicar una advertencia y una recomendacion aplicada por decision
   humana, con cambio de script y nueva aprobacion.
8. Recuperacion: mostrar las tres y cinco filas recuperadas de QA. Distinguir
   restaurar archivos, aplicar incrementales/redo y volver ONLINE.

No usar una copia completa de ocho minutos como unico eje de una exposicion
corta. Mostrar evidencia previa identificada y, si se requiere una ejecucion en
vivo, preparar una estrategia pequena. No dejar horarios activos al terminar
sin revisar la politica y la capacidad disponible.

## Afirmaciones que deben evitarse

- "Cumple el 100 %" o "nunca puede perder datos": las pruebas tienen alcance.
- "La nube es obligatoria": el PDF no la exige.
- "No hubo errores porque RMAN termino": tambien se comprueban piezas y conjuntos.
- "VALIDATE restaura la base": valida lectura; las dos recuperaciones QA si
  ejecutaron RESTORE y RECOVER.
- "Ya probamos perder todo el servidor": no se hizo; RECOVER utilizo archived
  logs persistentes disponibles, no una perdida simultanea de todos los medios.
- "Los 24/72/168 h son el RPO garantizado": son umbrales elegidos por el grupo.
- "El dibujo de recuperacion calcula toda la cadena real": es una explicacion
  conceptual; no un analizador completo del catalogo RMAN.
- "Todas las pruebas fueron Oracle real": las 41 automatizadas incluyen dobles
  de prueba; las 61 comprobaciones ampliadas/recomendaciones si usaron el entorno real.

## No exigido expresamente por este PDF

Despliegue en nube, correo de alertas, autenticacion multiusuario, cintas,
retencion automatica, un boton de restauracion destructiva o respaldos mientras
el proceso de la aplicacion esta apagado. Son posibles mejoras, no faltantes
automaticos del enunciado. Un simulacro de perdida total seria una ampliacion
de seguridad, no sustituye las evidencias minimas solicitadas.

## Orden recomendado antes del avance

1. Corregir y cubrir con pruebas la alerta de espacio para componentes.
2. Alinear el borrador/validacion con el flujo que se explicara al profesor.
3. Documentar la cobertura de alertas y consultar la interpretacion de ventana.
4. Ajustar textos de Quartz y verificacion; reunir las seis evidencias sin
   presentar reportes anteriores como auditorias del estado actual.
5. Adaptar la explicacion a los puntos que el usuario enviara y ensayarla.

## Fuentes locales

- [Enunciado identico al adjunto](enunciado-proyecto.pdf), paginas 1-13.
- [Analisis y diseno](analisis-y-diseno.md).
- [Pruebas ampliadas del 04/10](evidencias/pruebas-ampliadas-2026-10-04.md).
- [Automatizacion y pruebas del 28/09](evidencias/prueba-local-2026-09-28.md).
- [Resultados de recomendaciones](../runtime/pruebas-recomendaciones/20261004162948/resultados.json).
- [Resultados ampliados](../runtime/pruebas-ampliadas/20261004161551/resultados.json).
- [Captura de estado usada en esta revision](../tmp/review/compliance-state.json).

Los hallazgos de esta revision son adicionales al informe historico del 28/09;
no se estan presentando sus ocho problemas originales como si siguieran abiertos.
