# Guía para la exposición: Gestor de estrategias de respaldo RMAN

Todo se ejecuta en PowerShell desde la carpeta del proyecto:

    cd "C:\Users\Adrian Fernandez\Documents\ChatGPT\Administracion de Bases de datos"

## 1. Levantar todo (antes de clase)

| Paso | Comando | Qué esperar |
| --- | --- | --- |
| 1. Docker Desktop abierto | (abrir la aplicación) | Ícono de la ballena en verde |
| 2. Oracle del grupo | `docker start rman-lab` | Tarda unos 40 s |
| 3. Ver que Oracle está listo | `docker ps --filter name=rman-lab` | `Up ... (healthy)` |
| 4. Rama correcta | `git switch cambios-profesor-local` | `Already on 'cambios-profesor-local'` |
| 5. Compilar y correr las pruebas | `.\mvnw.cmd package` | `BUILD SUCCESS`; 106 pruebas, 0 fallos (2 a 3 min) |
| 6. Abrir la app (ventana 1) | `java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar target/gestor-rman-0.1.0.jar` | `Gestor RMAN disponible en http://127.0.0.1:8787` y `planificador EXTERNO` |
| 7. Abrir el Ejecutor (ventana 2) | `java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor` | `Ejecutor RMAN: lee ...catalogo-estrategias.txt cada 20 s` |
| 8. Navegador | http://127.0.0.1:8787 | Monitor con "Servidor conectado" |

Atajo: `.\scripts\preparar-entorno.ps1 -Iniciar` hace los pasos 2 a 7 solo (revisa Docker, Oracle, datos de prueba, redo multiplexado, compila y abre app + Ejecutor).

Si la compilación ya está hecha y no cambió nada: `.\mvnw.cmd package -DskipTests` (más rápido, sin pruebas).

Otros comandos útiles:

| Para | Comando |
| --- | --- |
| Correr una estrategia ya, sin esperar su horario | `java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA0001` |
| Consola SQL*Plus de la base del grupo | `docker exec -it -u oracle rman-lab sqlplus / as sysdba` |
| Consola RMAN | `docker exec -it -u oracle rman-lab rman target /` |
| Ver el catálogo plano | `Get-Content runtime\catalogo-estrategias.txt` |
| Ver los scripts generados | `Get-ChildItem runtime\scripts` |
| Liberar memoria (laboratorio individual) | `docker stop recu-lab` |
| Detener todo al final | `Ctrl+C` en las dos ventanas y `docker stop rman-lab` |

Antes de la exposición, en SQL*Plus, conviene revisar: `SELECT status FROM v$instance;` (OPEN) y `ARCHIVE LOG LIST;` (Archive Mode).

## 2. Qué es el sistema (la idea en 30 segundos)

Es un **gestor de estrategias de respaldo** para Oracle. El DBA no escribe scripts de RMAN a mano: define
**qué** respaldar, **cómo**, **cuándo** y **dónde**, la aplicación genera y valida el script, el DBA lo
**aprueba**, y un **Ejecutor** independiente lo corre a su hora y registra la evidencia (log y piezas). Si algo
falla, avisa por correo y permite volver a ejecutar.

Frase para abrir: "RMAN es el motor; nuestro sistema agrega planificación, aprobación, ejecución automática,
evidencia y alertas."

## 3. Arquitectura (cómo está construido)

| Pieza | Qué hace |
| --- | --- |
| Navegador (`src/main/resources/web`) | La interfaz: HTML, CSS y JavaScript sin frameworks |
| Servidor Java (`Main.java`) | Servidor HTTP propio en 127.0.0.1:8787; expone la API `/api/...` |
| `BackupService.java` | La lógica: guardar estrategias, validar, aprobar, ejecutar, verificar y alertas |
| `RmanScript.java` | Genera el script RMAN a partir de la estrategia y calcula su **huella** (hash) |
| `Catalog.java` (H2) | Base de datos interna de la app: estrategias, aprobaciones, ejecuciones (`runtime/catalog`) |
| `FlatCatalog.java` | El **catálogo plano** `runtime/catalogo-estrategias.txt`: una línea por estrategia |
| `ScriptStore.java` | Guarda cada script como `runtime/scripts/RMA000N.rma` |
| `Ejecutor.java` | Programa aparte: lee el catálogo cada 20 s y corre las estrategias con RMAN |
| `Rman.java` | Llama a RMAN y SQL*Plus dentro del contenedor con `docker exec` |
| `EmailNotifier.java` | Correo al DBA cuando un respaldo falla |
| `RecoveryAdvisor.java` | "Diagnosticar fallos": identifica qué le pasó a la base y propone la receta |
| Docker `rman-lab` | Oracle Free 23ai con la base de laboratorio (ARCHIVELOG, redo multiplexado) |

Flujo de datos: **Interfaz → API Java → genera RMA000N.rma + línea en el catálogo → Ejecutor → RMAN en
Docker → log y piezas → catálogo e historial → interfaz**.

## 4. Recorrido de la aplicación, pantalla por pantalla

### Monitor (pantalla inicial)

- **Indicadores arriba:** estrategias en operación, último respaldo correcto, resultados de los últimos 7 días y alertas abiertas.
- **Línea de tiempo:** últimas 24 h y próximas 24 h por estrategia (verde exitoso, rojo fallido, círculo vacío programado).
- **Estado por estrategia:** antigüedad del último respaldo frente al objetivo de su prioridad.
- **Control preventivo:** alertas, advertencias y recomendaciones (por ejemplo, "sin respaldo dentro de su objetivo", "falta respaldar archived logs").
- **Bitácora:** acciones registradas (creación, aprobación, ejecuciones).

Qué decir: "El DBA ve de un vistazo si la protección está al día, sin revisar logs."

### Estrategias → Nueva estrategia (el constructor)

1. **Información general:** nombre, descripción (qué protege y por qué), base de datos, responsable, **prioridad** (Alta = 24 h, Media = 72 h, Baja = 7 días como objetivo) y si está activa.
2. **Qué respaldar:** base completa, **tablespaces** (se marcan varios con clic y cada uno tiene **su propia prioridad**: el script respalda primero los de prioridad alta), datafiles o solo componentes. Casillas: **redo en línea**, **archived logs**, **control file**, **SPFILE**. El botón **"Full: marcar todo"** marca todo; si no, se indica que es parcial.
3. **Cómo respaldar:** completo, incremental **nivel 0**, nivel 1 **diferencial** o **acumulativo**; compresión y verificación posterior (`VALIDATE`).
4. **Cuándo:** fecha de inicio, frecuencia (diaria, semanal con **días de la semana**, por intervalo o **una vez / no cíclica**), **horas** y ventana máxima.
5. **Destino:** carpeta en el servidor Oracle (`/opt/oracle/backup`) y espacio libre.
6. Abajo se ve el **script RMAN generado en vivo**.

Qué decir: "El DBA responde qué, cómo, cuándo y dónde; el script lo genera la aplicación, sin errores de sintaxis."

### Revisar y aprobar

- Muestra validaciones (errores bloquean; advertencias informan: espacio, frecuencia insuficiente para la prioridad, ARCHIVELOG).
- Muestra el **script** y su **huella** (hash).
- Al aprobar: se genera `runtime/scripts/RMA000N.rma` (número consecutivo) y su línea en el catálogo plano con `APROBADA=SI` y la huella.

Qué decir: "Solo se ejecuta lo aprobado. Si alguien edita la estrategia o el archivo .rma, la huella cambia y hay que aprobar de nuevo; el Ejecutor se niega a correr un script modificado."

### Catálogo

- La tabla del archivo plano: código RMA, estrategia, qué respalda y con qué prioridad, cuándo, si está aprobada, última ejecución, resultado y pieza.
- Botones: **Ver script .rma**, **Ver archivo plano** (lo que leería un auditor), **Ver log de la última ejecución** y **Ejecutar ahora**.

Qué decir: "El catálogo es texto plano: un auditor lo abre con el Bloc de notas y ve toda la política de respaldo."

### El Ejecutor (ventana aparte)

- Cada 20 s lee el catálogo línea por línea.
- Si a una estrategia activa y aprobada le toca el día y la hora, comprueba la huella y ejecuta `rman target / cmdfile=RMA000N.rma log=...` en el contenedor.
- Escribe en el catálogo la fecha, el resultado (EXITOSO / FALLIDO), las piezas y la ruta del log; la app lo incorpora al historial.
- Si coinciden varias, corre primero la de prioridad alta. Si la PC estuvo apagada, corre solo el horario más reciente que se perdió.

Qué decir: "Separamos quien diseña (el DBA en la app) de quien ejecuta (el Ejecutor), como pidió el profesor."

### Ejecuciones

- Historial de cada ejecución (manual, programada o del Ejecutor) con estado.
- Al abrirla: evidencia (inicio, fin, duración, piezas, huella), el **script**, el **registro RMAN** completo y la **verificación**.
- Si falló: botón **"Volver a ejecutar ahora"**, y el DBA recibe un **correo** (si el SMTP está configurado).

### Bases de datos

- **Comprobar conexión:** versión, modo de archivado, PDBs, tablespaces, datafiles, archived logs y espacio en el destino.
- **Diagnosticar fallos:** identifica qué le pasó a la base (contenedor caído, instancia en NOMOUNT/MOUNT, datafiles, control files o redo borrados, PDB cerrado, tablespace offline, usuarios bloqueados, tablas en la papelera, filas faltantes) y propone el **paso a paso** con los comandos para SQL*Plus, RMAN o PowerShell. Solo lee: no restaura nada; el DBA decide.
- **Tomar foto del estado:** guarda el SCN y las filas de cada tabla para detectar después pérdidas de datos.

## 5. Tipos de respaldo: cuándo sí y cuándo no

Hay dos decisiones distintas que se confunden por la palabra "Full":

- **Alcance (QUÉ):** base completa, tablespaces, datafiles o solo componentes. El botón **"Full: marcar todo"** elige base completa + redo + archived logs + control file + SPFILE.
- **Tipo (CÓMO):** **Completo (FULL)** copia todos los bloques usados; los **incrementales** copian según el nivel.

Por eso **una base completa sí puede ser incremental** (`BACKUP INCREMENTAL LEVEL 0 DATABASE` es lo habitual): "Full"
de alcance no significa "completo" de tipo.

| Tipo | Cuándo sí | Cuándo no |
| --- | --- | --- |
| Completo (FULL) | Copias únicas o esporádicas (antes de un cambio grande o una migración); bases pequeñas | Como base de incrementales: un nivel 1 no lo reconoce y copia todo otra vez |
| Nivel 0 | Base semanal de la cadena; después de un `OPEN RESETLOGS` o al agregar tablespaces o datafiles | Varias veces al día: pesa y tarda como un completo |
| Nivel 1 diferencial | Respaldos diarios con poco espacio o ventana corta | Si la recuperación debe ser rápida (hay que aplicar todos los nivel 1); sin nivel 0 previo |
| Nivel 1 acumulativo | Respaldos diarios con recuperación rápida (nivel 0 + último acumulativo) | Bases con muchos cambios y poco espacio (crece hasta el siguiente nivel 0); sin nivel 0 previo |

| Alcance | ¿Admite incremental? | Por qué |
| --- | --- | --- |
| Base completa | Sí | Son datafiles; nivel 0 de la base es la base de toda la cadena |
| Tablespaces / datafiles | Sí | El nivel 0 debe cubrir esos mismos tablespaces o datafiles (un nivel 0 de la base completa también sirve) |
| Solo componentes | No | Archived logs, control file y SPFILE se copian siempre completos; la app fija el tipo en Completo |

Validaciones de la app: al aprobar un nivel 1 recuerda que necesita un nivel 0; advierte si un nivel 1 está
programado "una vez" (los incrementales se planifican en cadena); el Monitor recomienda "sin nivel 0
verificado" si falta la base; y en el constructor, el panel **"Cuándo sí / Cuándo no"** cambia según el
alcance y el tipo elegidos.

## 6. Guion de demostración (8 a 10 minutos)

1. **Monitor** (1 min): explicar los indicadores y el control preventivo.
2. **Nueva estrategia** (3 min): dos tablespaces (LAB_DATOS alta, USERS media), archived logs y control file, "una vez" dentro de 3 minutos. Mostrar el script en vivo con un BACKUP por prioridad.
3. **Aprobar** (1 min): validaciones y huella.
4. **Catálogo** (1 min): la línea nueva y el `.rma` en el modal; abrir el archivo plano.
5. **Ejecutor** (2 min): a la hora indicada aparece en su ventana `RMA000N ... -> RMAN` y luego `EXITOSO`. Volver al Catálogo y abrir el log.
6. **Cierre** (1 min): Diagnosticar fallos en Bases de datos ("si la base cae, la app dice qué pasó y cómo recuperarla").

Plan B si el Ejecutor tarda: `java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA000N` o el botón "Ejecutar ahora".

## 7. Preguntas probables

| Pregunta | Respuesta |
| --- | --- |
| ¿Por qué no usar solo RMAN o un script? | RMAN es el motor; el sistema agrega planificación, aprobación, ejecución automática, evidencia, alertas y diagnóstico |
| ¿Qué es la huella? | Un hash del script aprobado; si el script cambia, deja de coincidir y no se ejecuta sin una nueva aprobación |
| ¿Qué pasa si la app se cierra? | El Ejecutor sigue corriendo los horarios: es un proceso aparte que solo lee el catálogo |
| ¿Y si la PC se apaga? | No hay respaldos mientras está apagada; al volver, el Ejecutor corre solo el horario más reciente que se perdió |
| ¿Por qué ARCHIVELOG? | Sin archived logs solo se puede volver al momento del respaldo; con ellos se recupera hasta el último commit |
| ¿Por qué redo multiplexado? | Si se pierde un archivo de redo, Oracle sigue con la copia y no se pierden datos |
| ¿Cómo saben que el respaldo sirve? | Verificación con `VALIDATE`, el log de RMAN y las piezas registradas; además se practicaron recuperaciones reales |
| ¿Nivel 0 vs nivel 1? | Nivel 0 copia todo y es la base; nivel 1 copia solo los bloques cambiados desde el nivel 0 (acumulativo) o desde el último incremental (diferencial) |
| ¿Está listo para producción? | Es un sistema funcional de laboratorio con pruebas automáticas (106) y pruebas reales con Oracle; en producción faltaría, por ejemplo, autenticación de usuarios y respaldos en otro servidor |
