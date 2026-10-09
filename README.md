# Gestor de estrategias de respaldo Oracle (RMAN)

Proyecto del curso EIF402 Administracion de Bases de Datos (UNA, II ciclo 2026).
Herramienta para definir, automatizar y monitorear estrategias de respaldo de bases
Oracle con RMAN, orientada al control preventivo de los riesgos de disponibilidad e
integridad.

    Que respaldar -> Como -> Cuando -> Destino -> script RMAN -> aprobacion
    -> programacion -> ejecucion -> evidencia -> monitoreo y alertas

- Constructor de estrategias con el script RMAN generado en vivo y validado contra
  la base (tablespaces, datafiles, destino, espacio y modo de archivado).
- Aprobacion del script por el administrador; si la estrategia cambia, la
  aprobacion se invalida.
- Ejecucion programada, con evidencia por ejecucion: piezas comprobadas en disco,
  errores de RMAN, estados Exitoso / Con advertencias / Fallido y verificacion con
  VALIDATE BACKUPSET sobre los conjuntos identificados de cada ejecucion.
- Monitor con linea de tiempo, control preventivo (alertas, advertencias,
  recomendaciones e informativas) y ciclo de cada estrategia.

## Requisitos

| Herramienta | Detalle |
| --- | --- |
| Docker Desktop | Con al menos 8 GB de RAM en el equipo (el laboratorio usa 3 GB) y unos 10 GB libres |
| JDK 21 o superior | Por ejemplo Eclipse Temurin: https://adoptium.net |
| Git | Para clonar el repositorio |

No hace falta instalar Maven ni Oracle: el proyecto trae el Maven Wrapper y el
script descarga la imagen oficial `container-registry.oracle.com/database/free`.
En Windows esta fijada por digest a la imagen probada por el grupo, no a `latest`.
El laboratorio probado es Linux/amd64 (equipos Windows Intel/AMD, Docker Desktop
en modo de contenedores Linux); Windows ARM requiere validacion aparte.

**Para los integrantes del grupo:** [guia de trabajo en Windows](docs/equipo-windows.md).
Todos tienen la misma estructura inicial, pero datos, estrategias e historial
independientes. GitHub no sincroniza las bases de datos.

## Instalacion (primera vez)

Con Docker Desktop abierto:

Windows (PowerShell):

    git clone https://github.com/Adrian123CRff/Redolog.git
    cd Redolog
    powershell -ExecutionPolicy Bypass -File .\scripts\preparar-entorno.ps1 -Iniciar

macOS o Linux:

    git clone https://github.com/Adrian123CRff/Redolog.git
    cd Redolog
    ./scripts/preparar-entorno.sh --iniciar

El script prepara el laboratorio y conserva los datos existentes:

1. Comprueba Java 21+ y Docker.
2. Crea el contenedor `rman-lab` (Oracle 26ai Free en ARCHIVELOG, puerto
   127.0.0.1:1524) o reutiliza el existente. La primera vez descarga unos 4 GB y
   Oracle tarda varios minutos en quedar listo.
3. Crea los datos de prueba (tablespace FREEPDB1:LAB_DATOS y tabla
   LAB_DEMO.PEDIDOS).
4. Deja los archived logs y el autobackup del control file en almacenamiento
   persistente.
5. Compila con `mvnw` y, con `-Iniciar` en Windows, ejecuta la aplicacion en la
   misma terminal. Abre http://127.0.0.1:8787 en tu navegador.

En Windows se detiene si encuentra un contenedor ajeno, otra imagen en el
laboratorio existente o un volumen sin contenedor. No los reemplaza ni borra.

Los respaldos quedan en `runtime/backups` dentro del proyecto. La carpeta
`runtime/` no se sube a Git: cada integrante tiene su propio catalogo e historial.

## Uso diario

    powershell -ExecutionPolicy Bypass -File .\scripts\preparar-entorno.ps1 -Iniciar

O, si el contenedor ya esta creado:

    docker start rman-lab
    java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"

Ejecutalo desde la carpeta del proyecto. Primeros pasos en la aplicacion:

1. Bases de datos: "Comprobar conexion" lee el modo de archivado, los tablespaces
   y el espacio disponible.
2. Estrategias: editar EST001 o crear una nueva, revisar el script y aprobarlo.
3. Monitor: seguir la linea de tiempo y atender el control preventivo.

## Correo de alertas al DBA

Cuando una ejecucion termina Fallida, Incierta o Con advertencias, la herramienta
envia un correo al DBA de esa base con la estrategia, el error de RMAN y la ruta
del registro. Cada envio (o su omision) queda en la bitacora.

1. En Bases de datos, registra la base con su "Correo del DBA".
2. Define el servidor SMTP con variables de entorno antes de iniciar (la
   contrasena no se guarda en el catalogo ni en el repositorio):

        GESTOR_SMTP_HOST=smtp.gmail.com
        GESTOR_SMTP_PORT=587               (opcional, 587 por defecto)
        GESTOR_SMTP_USER=cuenta@gmail.com
        GESTOR_SMTP_PASSWORD=contrasena-de-aplicacion
        GESTOR_SMTP_FROM=cuenta@gmail.com  (opcional, usa el usuario)
        GESTOR_SMTP_STARTTLS=true          (opcional, true por defecto)

3. En la tarjeta de la base, "Enviar correo de prueba" confirma que llega.

Sin `GESTOR_SMTP_HOST` el correo queda desactivado y la interfaz lo indica. El
modo simulacion nunca envia correos.

## Scripts .rma

Cada estrategia deja en `runtime/scripts/` un archivo `RMA0001.rma`, `RMA0002.rma`...
(un codigo consecutivo por estrategia, que se conserva al editarla) con el script
RMAN vigente, que se puede revisar o ejecutar a mano. Cada ejecucion conserva ademas
su propia copia (`script.rma`, `verify.rma`) como evidencia.

## Catalogo en archivo plano y ejecutor

`runtime/catalogo-estrategias.txt` tiene una linea por estrategia: codigo, script,
base, prioridad, que respalda, dias, horas, si esta aprobada (con la huella del
script), ultima ejecucion, resultado, piezas y log. Se ve en la vista **Catalogo**.

El programa `edu.respaldos.Ejecutor` lee ese archivo y ejecuta con RMAN las
estrategias que tocan, pidiendole el log, y actualiza el catalogo. Para usarlo en
lugar del planificador de la aplicacion:

    java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.planificador=externo" -jar target/gestor-rman-0.1.0.jar
    java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor              (en otra terminal)
    java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA0001

Detalle en [docs/cambios-clase-2026-10-05.md](docs/cambios-clase-2026-10-05.md).

## Modo robot (sin interfaz web)

    java -jar target/gestor-rman-0.1.0.jar --robot

Arranca solo el planificador y los avisos, sin servidor web, para dejarlo en segundo
plano. Para que arranque con Windows, crea una tarea en el Programador de tareas
("Al iniciar sesion") que ejecute ese comando desde la carpeta del proyecto. El
robot y la interfaz comparten el catalogo, por lo que no pueden estar abiertos a la
vez: cierra el robot antes de abrir la interfaz y viceversa.

## Modo simulacion (sin Oracle)

Para probar la interfaz en un equipo sin Docker, o para la demostracion publica:

    java -Dapp.mode=simulacion -jar target/gestor-rman-0.1.0.jar

Carga dos bases y cinco estrategias de ejemplo con una semana de historial. Las
ejecuciones de RMAN son simuladas y la pantalla lo indica. En este modo el
servidor escucha en todas las interfaces de red; el modo real solo escucha en
127.0.0.1.

## Demostracion publica

La version real necesita Oracle y Docker en la misma maquina, por eso no se puede
publicar en Vercel ni en otros hostings gratuitos. El repositorio incluye un
Dockerfile y `render.yaml` para publicar el modo simulacion gratis en Render. Ver
[docs/despliegue.md](docs/despliegue.md).

## Pruebas

    .\mvnw.cmd test        (Windows)
    ./mvnw test            (macOS o Linux)

Prueba local de extremo a extremo en Windows, con `rman-lab` y la aplicacion
real ya iniciados (no sirve contra el modo simulacion):

    powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-flujo-local.ps1

Crea una estrategia de prueba para FREEPDB1:LAB_DATOS, comprueba los bloqueos de
aprobacion, hace un respaldo real con verificacion y observa un disparo automatico.
No restaura ni borra archivos; conserva las copias generadas. Al terminar desactiva
la estrategia de prueba y guarda resultados en `runtime/pruebas-locales/`.
Requiere espacio disponible y puede tardar varios minutos. Si se interrumpe la
terminal o una operacion queda incierta, revisar manualmente la estrategia antes
de repetir la prueba; el script nunca libera una base incierta por su cuenta.

En PowerShell, las opciones Java con puntos deben ir entre comillas. En este
equipo Windows el inicio comprobado es:

    java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"

Prueba de recuperacion en el laboratorio (destructiva, solo sobre `rman-lab`):

    powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-recuperacion.ps1

Prueba ampliada con copia completa, incrementales y recuperacion aislada por
tablespace (Oracle real, modifica solo objetos nuevos `QA_RMAN_*`):

    powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-ampliada.ps1

Requiere la aplicacion local, ningun trabajo activo o programado y espacio libre
superior al tamano de los datafiles mas 4 GiB. Crea un esquema sin autenticacion
y un tablespace de 16 MiB, prueba nivel 0, diferencial y acumulativo, y restaura
dos veces hacia archivos nuevos. Conserva las piezas, el historial, los objetos
QA y sus archivos originales. No restaura toda la instancia ni modifica
`LAB_DEMO.PEDIDOS`. Guarda resultados y tamanos reales en
`runtime/pruebas-ampliadas/`. Puede tardar mas de diez minutos.

Si falla o se interrumpe una recuperacion, revisar sus registros antes de repetir:
el tablespace QA podria quedar offline. No liberar trabajos inciertos ni borrar
archivos como parte de la repeticion. Las estrategias de esta prueba nacen
desactivadas y sin horarios; no generan respaldos periodicos.

Comprobacion del control preventivo y su bitacora, sin crear copias:

    powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-recomendaciones.ps1

Ejecutar cuando no haya respaldos en curso. Crea una estrategia QA desactivada,
aplica recomendaciones de archived logs y verificacion, comprueba que el script
cambie y exija una nueva aprobacion, y conserva evidencia en
`runtime/pruebas-recomendaciones/`. No modifica estrategias existentes.

## Estructura

    src/main/java/edu/respaldos/
      Main.java            servidor HTTP y API (/api)
      BackupService.java   flujo: validar, aprobar, programar, ejecutar, evaluar evidencia
      RmanScript.java      construccion y validacion del script RMAN
      Schedules.java       "cuando" como expresiones cron (Quartz)
      Alerts.java          reglas del control preventivo
      Rman.java            ejecucion de RMAN y SQL*Plus dentro del contenedor
      SimulatedRman.java   sustituto sin Oracle para el modo simulacion
      Demo.java            datos de ejemplo del modo simulacion
      Catalog.java         catalogo local H2
      FlatCatalog.java     catalogo en archivo plano (catalogo-estrategias.txt)
      Ejecutor.java        programa aparte que lee el catalogo plano y ejecuta RMAN
    src/main/resources/web/  interfaz (HTML, CSS, JavaScript)
    scripts/                 instalacion del entorno y scripts del laboratorio
    docs/                    enunciado, revision, analisis y diseno, evidencias, despliegue

## Documentacion

- [Enunciado del proyecto](docs/enunciado-proyecto.pdf)
- [Revision del avance contra el enunciado](docs/revision-enunciado.md)
- [Analisis y diseno](docs/analisis-y-diseno.md)
- [Evidencia: prueba de recuperacion](docs/evidencias/prueba-recuperacion.md)
- [Pruebas locales ampliadas del 04/10/2026](docs/evidencias/pruebas-ampliadas-2026-10-04.md)
- [Despliegue](docs/despliegue.md)
- [Cambios pedidos en la clase del 05/10/2026](docs/cambios-clase-2026-10-05.md)
- [Fallos de medio fisico: riesgo, estrategia y recuperacion](docs/fallos-medio-fisico.md)

## Problemas frecuentes

| Sintoma | Solucion |
| --- | --- |
| "no se puede cargar ... la ejecucion de scripts esta deshabilitada" | Usa `powershell -ExecutionPolicy Bypass -File ...` como en los ejemplos |
| "Docker no responde" | Abre Docker Desktop y espera a que indique que esta en ejecucion |
| "Oracle no quedo listo a tiempo" | Revisa `docker logs rman-lab`; la primera creacion puede tardar mas en equipos lentos |
| El puerto 1524 u 8787 esta ocupado | Cierra el otro programa; `oradb` y otras bases usan 1521, que no choca |
| "Ya hay una instancia usando este catalogo" | La aplicacion ya esta abierta en otra ventana |
