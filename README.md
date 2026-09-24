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
  CROSSCHECK y RESTORE ... VALIDATE.
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

El script hace, y se puede repetir sin riesgo:

1. Comprueba Java 21+ y Docker.
2. Crea el contenedor `rman-lab` (Oracle 26ai Free en ARCHIVELOG, puerto
   127.0.0.1:1524) o reutiliza el existente. La primera vez descarga unos 4 GB y
   Oracle tarda varios minutos en quedar listo.
3. Crea los datos de prueba (tablespace FREEPDB1:LAB_DATOS y tabla
   LAB_DEMO.PEDIDOS).
4. Deja los archived logs y el autobackup del control file en almacenamiento
   persistente.
5. Compila con `mvnw` y, con `-Iniciar`, abre http://127.0.0.1:8787.

Los respaldos quedan en `runtime/backups` dentro del proyecto. La carpeta
`runtime/` no se sube a Git: cada integrante tiene su propio catalogo e historial.

## Uso diario

    powershell -ExecutionPolicy Bypass -File .\scripts\preparar-entorno.ps1 -Iniciar

O, si el contenedor ya esta creado:

    docker start rman-lab
    java -jar target/gestor-rman-0.1.0.jar

Ejecutalo desde la carpeta del proyecto. Primeros pasos en la aplicacion:

1. Bases de datos: "Comprobar conexion" lee el modo de archivado, los tablespaces
   y el espacio disponible.
2. Estrategias: editar EST001 o crear una nueva, revisar el script y aprobarlo.
3. Monitor: seguir la linea de tiempo y atender el control preventivo.

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

Prueba de recuperacion en el laboratorio (destructiva, solo sobre `rman-lab`):

    powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-recuperacion.ps1

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
    src/main/resources/web/  interfaz (HTML, CSS, JavaScript)
    scripts/                 instalacion del entorno y scripts del laboratorio
    docs/                    enunciado, revision, analisis y diseno, evidencias, despliegue

## Documentacion

- [Enunciado del proyecto](docs/enunciado-proyecto.pdf)
- [Revision del avance contra el enunciado](docs/revision-enunciado.md)
- [Analisis y diseno](docs/analisis-y-diseno.md)
- [Evidencia: prueba de recuperacion](docs/evidencias/prueba-recuperacion.md)
- [Despliegue](docs/despliegue.md)

## Problemas frecuentes

| Sintoma | Solucion |
| --- | --- |
| "no se puede cargar ... la ejecucion de scripts esta deshabilitada" | Usa `powershell -ExecutionPolicy Bypass -File ...` como en los ejemplos |
| "Docker no responde" | Abre Docker Desktop y espera a que indique que esta en ejecucion |
| "Oracle no quedo listo a tiempo" | Revisa `docker logs rman-lab`; la primera creacion puede tardar mas en equipos lentos |
| El puerto 1524 u 8787 esta ocupado | Cierra el otro programa; `oradb` y otras bases usan 1521, que no choca |
| "Ya hay una instancia usando este catalogo" | La aplicacion ya esta abierta en otra ventana |
