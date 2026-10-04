# Correcciones y validacion

Fecha: 28 de septiembre de 2026.

## Estado

Se aplicaron correcciones a los ocho hallazgos de la auditoria. La suite ampliada
contiene 33 pruebas automatizadas y paso sin fallos. Las pruebas del servicio usan
un ejecutor simulado y catalogos temporales; no son ejecuciones contra Oracle.

Tambien se compilo el ejecutable y se reviso la interfaz con Playwright en
escritorio (1440 px) y movil (390 px), sin errores JavaScript ni desbordamiento
horizontal de la pagina. La comprobacion recorrio una verificacion simulada,
su relacion con el respaldo y el script de conjuntos exactos. El servidor de
esta comprobacion utiliza un catalogo temporal separado del laboratorio real.

Esto significa que hay soluciones implementadas y comprobadas en pruebas, no que
ya se haya certificado la recuperabilidad real de todas las estrategias.

## Solucion por hallazgo

| Hallazgo | Solucion aplicada | Comprobacion |
| --- | --- | --- |
| H1: verificacion generica | Se obtienen las claves de V$BACKUP_SET a partir de cada pieza producida y se ejecuta VALIDATE BACKUPSET por conjunto. La evidencia registra piezas, claves y respaldo verificado | Prueba de script exacto, vinculo a la ejecucion y rechazo si cambian las claves; pendiente integracion con Oracle real |
| H2: ejecucion sin revalidacion | Diagnostico actualizado al aprobar y antes de despachar; errores bloquean el envio y quedan en el historial | Conexion no disponible al aprobar y destino desaparecido despues de aprobar |
| H3: intervalos reiniciados por dia | SimpleTrigger continuo anclado a fecha/hora, con calendario de dias permitidos | Cada 5 h desde 20:00 cruza medianoche correctamente; dias excluidos no reinician el intervalo |
| H4: timeout libera base | La verificacion interrumpida por timeout conserva INCIERTO, bloqueo y evidencia parcial | No se admite otro respaldo hasta la liberacion explicita |
| H5: proxima ejecucion incorrecta | Se combinan ocurrencias de todos los disparadores antes del limite | A las 10:00, con 08:00/12:00/18:00, devuelve 12:00 del mismo dia |
| H6: verificacion sin evidencia vigente | Estado estructurado ligado a un respaldo; una comprobacion fallida posterior prevalece y genera alerta | Activar la opcion no certifica copias antiguas; exito seguido por fallo deja de figurar verificado |
| H7: limite global de 500 | Se elimina el recorte de la consulta compartida por historial, alertas y recuperacion de bloqueos | 502 registros y reinicio con una ejecucion incierta antigua |
| H8: evidencia de otro alcance | Huella del alcance, base y destino; alertas e indicadores solo usan evidencia compatible | Cambiar el alcance no reutiliza el exito anterior; historial conservado |

## Decisiones de funcionamiento

- La aprobacion incluye el horario y la politica en los comentarios del script.
  Cambiar el horario exige aprobar de nuevo; el control de omisiones empieza desde
  esa aprobacion, no desde un horario anterior.
- La huella de cobertura es independiente del horario y de la opcion de verificar
  al terminar. Cambiar esas opciones no modifica lo que realmente contiene una
  copia existente ni la convierte en verificada.
- Los intervalos cuentan horas transcurridas desde el inicio. Un disparo en un
  dia no permitido se omite; el intervalo no vuelve a empezar al dia siguiente.
- La ventana sigue siendo una duracion maxima que genera advertencia. No es una
  franja con hora de apertura/cierre; confirmar esta interpretacion con el profesor.
- Una verificacion manual apunta al ultimo respaldo correcto del alcance actual
  que tenga conjuntos identificados. Antes de enviarla, se comprueba que las piezas
  siguen correspondiendo a las mismas claves del catalogo Oracle.
- Leer los conjuntos no garantiza por si solo que haya todo el redo y toda la
  cadena incremental necesaria para recuperar hasta un instante elegido.
- Un respaldo cuyos conjuntos no se logran identificar no recibe certificacion
  de verificacion. No se usa otra copia o un TAG generico como sustituto silencioso.

## Compatibilidad con los datos existentes

No se borro ni modifico el catalogo real del usuario durante el desarrollo.
Las ejecuciones antiguas se siguen leyendo y mostrando en el historial. Al no
tener la nueva evidencia estructurada, no se usan para certificar el alcance
actual. Se muestra una advertencia cuando solo existe evidencia anterior.

Las estrategias existentes necesitaran nueva aprobacion porque el script incluye
la politica revisada. Antes de aprobar, revisar sus horarios: INTERVALO ahora tiene
semantica continua, distinta del comportamiento diario defectuoso anterior.

Para una instalacion con mucho historial conviene paginar la interfaz y crear
consultas operativas independientes. Por ahora se prioriza no omitir evidencia;
la consulta completa puede crecer en consumo de memoria.

## Documentacion actualizada

El analisis y diseno incorpora los criterios de disponibilidad y proteccion por
tipo de respaldo, corrige FULL como "una sola pieza" y distingue restaurar,
recuperar y medir antiguedad. Tambien aclara que las FK del diagrama son relaciones
conceptuales sobre el catalogo JSON, no restricciones fisicas implementadas.

Referencias tecnicas para la implementacion:

- [Oracle: VALIDATE](https://docs.oracle.com/en/database/oracle/oracle-database/21/rcmrf/VALIDATE.html).
- [Oracle: V$BACKUP_SET](https://docs.oracle.com/en/database/oracle/oracle-database/26/refrn/V-BACKUP_SET.html).

## Validacion de laboratorio

En la revision inicial Oracle estaba detenido. Posteriormente, el 28/09/2026,
se inicio rman-lab y se completo el flujo real con 20 comprobaciones correctas:
aprobacion, respaldo manual, verificacion y respaldo programado por Quartz.
Los datos de prueba conservaron sus cinco registros y las estrategias QA quedaron
desactivadas. La evidencia detallada esta en evidencias/prueba-local-2026-09-28.md.
No se realizo una recuperacion destructiva en esta comprobacion.

El 04/10/2026 se completo la ampliacion: 41 pruebas automatizadas, 51
comprobaciones reales ampliadas y 10 de recomendaciones, sin fallos. Incluye
recuperacion diferencial y acumulativa en un tablespace QA nuevo, componentes
por separado y RESTORE DATABASE VALIDATE. El detalle y las limitaciones estan
en evidencias/pruebas-ampliadas-2026-10-04.md. No equivale a una restauracion de
toda la instancia en otro servidor.

Lista para repetir la demostracion antes de entregar:

1. Iniciar el laboratorio y comprobar Oracle/RMAN desde la aplicacion.
2. Revisar y aprobar nuevamente una estrategia de prueba.
3. Ejecutar un respaldo pequeno y comprobar las claves de conjuntos obtenidas.
4. Verificar esos conjuntos y conservar el script y la salida real.
5. Observar un disparo programado, no solamente una ejecucion manual.
6. Preparar una falla controlada y una recomendacion aplicada con su evidencia.
7. Repetir una recuperacion solo en el laboratorio y bajo control del administrador.

La prueba real no equivale a una recuperacion completa ni cubre todas las
modalidades posibles. Las evidencias deben distinguir pruebas reales de
simulaciones. No se agregaron requisitos ajenos al enunciado, como correo,
nube o cintas obligatorias.
