# Proyecto: automatizacion de estrategias de respaldo Oracle

Estado: requisitos iniciales derivados de los apuntes y la transcripcion de clase.
Lenguaje elegido con el usuario: Java. Equipo local: Windows 11.
Oracle localizado en Docker; ver docs/entorno-y-plan.md para el diagnostico actual.
Pendiente: seleccionar el laboratorio RMAN y criterios formales de evaluacion.

## Objetivo de la clase

Construir una aplicacion que permita definir estrategias de respaldo para varias
bases de datos Oracle, generar los scripts RMAN correspondientes, ejecutarlos en
horarios establecidos y registrar sus resultados. Ante fallos, debe poder avisar
al DBA por correo. La estrategia debe justificarse por la importancia de los
datos y la perdida que la organizacion puede aceptar.

El profesor permite Python, Java o C++. Java fue tambien un ejemplo de un proyecto
anterior. Menciona aproximadamente dos semanas de trabajo y revision de avances;
la transcripcion no fija una fecha exacta de entrega.

## Funciones solicitadas

1. Registrar multiples bases de datos y asociar estrategias a cada una.
2. Definir el nombre de cada estrategia y la prioridad de los datos protegidos.
3. Seleccionar el alcance: base de datos o tablespaces determinados.
4. Seleccionar el metodo de respaldo y si incluye archived logs y control file.
5. Asignar uno o varios horarios a cada estrategia.
6. Generar archivos de comandos RMAN a partir de la configuracion.
7. Ejecutar automaticamente los archivos cuando corresponda.
8. Registrar inicio, fin, resultado y log de cada ejecucion.
9. Identificar fallos y emitir una alerta al correo configurado del DBA.
10. Presentar resultados y pruebas que sustenten la estrategia de recuperacion.

La transcripcion menciona tablas individuales. Su inclusion y el mecanismo
apropiado deben aclararse: no debe suponerse que existe un BACKUP TABLE en RMAN.
Tambien queda por aclarar si generar estrategias automaticamente significa
generar sus scripts desde un formulario o recomendar horarios y metodos mediante
reglas. La demostracion del profesor describe principalmente el primer caso.

## Ejemplo del profesor

Estos nombres y horarios son ejemplos de configuracion, no valores obligatorios.

| Base | Estrategia | Alcance | Horarios | Metodo mencionado |
| --- | --- | --- | --- | --- |
| 14 | EST001 | T1 y T3 | 13:00, 15:00, 18:00, 21:00 | Parcial completo |
| 14 | EST002 | Base completa | 02:00 | Full backup |
| 14 | EST003 | T4 | 18:00, 02:00 | Parcial |

El sistema debe permitir otras bases y estrategias sin cambios en el codigo.

## Precisiones para la implementacion

- Separar alcance (base o tablespaces) de metodo (full o incremental).
- Un full no equivale a un incremental nivel 0 como base de una cadena incremental.
- RMAN permite respaldos online en ARCHIVELOG. No introducir OFFLINE o SHUTDOWN
  como pasos generales de las estrategias.
- Traducir "total plus" a componentes explicitos; la etiqueta de clase no es un
  comando RMAN. "Incompleto" necesita aclaracion antes de convertirlo en un tipo
  de respaldo disponible.
- Los respaldos de archived logs deben distinguirse de los online redo logs.
- Un script puede ejecutarse con CMDFILE y guardar salida mediante LOG.
- Un archivo de log creado no demuestra exito. Registrar el resultado real de la
  ejecucion y diferenciar ejecucion correcta de recuperacion comprobada.
- La perdida recuperable no se deduce solo de la hora del ultimo backup:
  tambien depende de los respaldos disponibles y de la continuidad del redo.

Estas precisiones se contrastaron con la documentacion oficial indicada al final.
La compatibilidad exacta se verificara contra la version instalada.

## Diseno inicial propuesto

Flujo: formulario de estrategia -> almacenamiento -> generador RMAN ->
planificador -> ejecutor RMAN -> historial de resultados -> notificacion.

| Entidad | Datos principales |
| --- | --- |
| Base de datos | Identificador interno, nombre, servicio, entorno CDB/PDB, referencia de conexion |
| Estrategia | Base, nombre, prioridad, alcance, metodo, opciones, destino, estado activo |
| Objeto de estrategia | Tablespace seleccionado y prioridad cuando corresponda |
| Horario | Estrategia, hora, dias, zona horaria |
| Ejecucion | Estrategia y version, hora prevista, inicio, fin, estado, codigo de salida, log |
| Notificacion | Ejecucion, destinatario, estado de envio, intentos |
| Prueba de recuperacion | Respaldos usados, punto recuperado, duracion, evidencia |

El identificador 14 del ejemplo es un identificador de la aplicacion; no se debe
confundir automaticamente con el DBID real de Oracle.

Propuestas de funcionamiento para completar el diseno:

- Conservar el script exacto utilizado en cada ejecucion.
- Evitar ejecuciones duplicadas del mismo horario y gestionar coincidencias
  entre estrategias de una misma base.
- Definir que hacer con horarios perdidos durante un reinicio del programa.
- Mantener el planificador activo con esperas, sin un bucle que consuma CPU
  continuamente, y persistir los horarios y resultados.
- Separar referencias a credenciales de los scripts y logs.
- Resolver donde corre RMAN y donde se escriben los backups. Una conexion remota
  no implica que los archivos de respaldo se guarden en el equipo de la interfaz.
- Probar el correo con un destinatario de laboratorio configurado para ese fin.

Estas son decisiones propuestas de ingenieria, no exigencias textuales adicionales
del profesor. La tecnologia de interfaz y almacenamiento sigue pendiente.

## Secuencia de desarrollo y demostracion

1. Identificar version y edicion Oracle, sistema operativo, servicio/CDB/PDB,
   disponibilidad de RMAN y estado ARCHIVELOG.
2. Ejecutar y comprender un respaldo manual en el laboratorio; conservar evidencia.
3. Definir formularios, modelo de datos y generacion de scripts.
4. Integrar ejecucion manual desde la aplicacion, historial y lectura de resultados.
5. Incorporar horarios persistentes y control de duplicados.
6. Probar una ejecucion fallida y el registro de la alerta.
7. Demostrar restauracion y recuperacion en un entorno de pruebas, medir el
   tiempo empleado y comprobar que datos se recuperaron.

La demostracion deberia incluir al menos dos bases configuradas, varias
estrategias con horarios distintos, un respaldo real, un fallo identificado y
evidencia de recuperacion. Esta cobertura es una propuesta de aceptacion, no una
rubrica entregada por el profesor. Las simulaciones deben identificarse como tales.

## Informacion pendiente

- Version/edicion Oracle y estructura CDB/PDB del laboratorio.
- Sistema operativo y ubicacion del servidor Oracle y del proceso RMAN.
- Requisitos de interfaz y almacenamiento; el grupo prefiere Java.
- Alcance exigido para tablas individuales y generacion de recomendaciones.
- Definicion del profesor para los tipos "parcial completo" e "incompleto".
- Fecha de entrega y rubrica, si existen fuera de la transcripcion.

## Verificacion inicial de la instalacion nativa

- Java: JDK 25.0.1, comprobado con java -version.
- Maven: 3.9.16, comprobado con mvn -version.
- Windows 11, reportado por Maven.
- No se encontraron servicios Oracle ni sqlplus/rman en PATH.
- C:\dbhomeXE contiene solamente cfgtoollogs en la inspeccion realizada.
- El registro del instalador del 14 de septiembre de 2026 identifica Oracle
  Database 21c y un error fatal INS-32014: el directorio base C:\ fue rechazado
  por estar directamente en la raiz de una unidad.
- Estos hallazgos acreditan un intento de instalacion de 21c, no una instancia
  instalada y operativa. La busqueda de ejecutables en el perfil no fue exhaustiva.
- Docker Desktop esta instalado, pero su motor no estaba disponible. WSL lista
  Ubuntu y docker-desktop detenidos; no se inspeccionaron sus instalaciones internas.
- Falta identificar el programa o entorno con el que el usuario accede a Oracle.

Actualizacion: se localizaron las bases en Docker. oradb responde como Oracle AI
Database 26ai Free 23.26.2.0.0 y esta en ARCHIVELOG. Su imagen slim no contiene
RMAN. La imagen completa del contenedor Oracle-AdrianFer incluye RMAN
23.26.1.0.0, cuyo arranque se verifico sin conectarlo a una base. Ambos
contenedores tienen configurado el puerto 1521 del equipo; no pueden iniciarse
simultaneamente con esa configuracion. Ver entorno-y-plan.md.

## Fuentes

- Apuntes: apuntos de la clase de bases de datos.one, proporcionado por el usuario.
- Transcripcion: Texto pegado.txt, proporcionado por el usuario en esta conversacion.
- [Oracle 21c: Backing Up the Database](https://docs.oracle.com/en/database/oracle/oracle-database/21/bradv/backing-up-database.html)
- [Oracle 21c: RMAN Backup Concepts](https://docs.oracle.com/en/database/oracle/oracle-database/21/bradv/rman-backup-concepts.html)
- [Oracle 19c: RMAN, parametros del cliente](https://docs.oracle.com/en/database/oracle/oracle-database/19/rcmrf/RMAN.html)
