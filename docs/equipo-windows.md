# Laboratorio independiente para cada integrante (Windows)

Cada integrante ejecuta el mismo codigo y crea la misma estructura inicial de
Oracle en su computadora. Sus cambios en tablas, estrategias, respaldos e historial
son propios: no dependen de que otro integrante tenga su equipo encendido.

## Requisitos

- Git, JDK 21 o superior y Docker Desktop instalado y abierto.
- Docker Desktop configurado para contenedores Linux.
- Equipo Windows Intel/AMD de 64 bits. La imagen comprobada es Linux/amd64;
  Windows ARM no se ha validado para este laboratorio.
- Referencia del proyecto: al menos 8 GB de RAM en el equipo; Oracle tiene un
  limite de 3 GB. Reservar al menos 10 GB libres para comenzar y espacio adicional
  para respaldos. Ese valor no garantiza capacidad para todas las pruebas.
- Internet para la primera descarga de Oracle y dependencias de Java.

No necesitan instalar Oracle ni RMAN directamente en Windows. RMAN viene dentro
de la imagen de Oracle. El Dockerfile de la raiz es para la demostracion simulada,
no para preparar este laboratorio real.

## Primera instalacion

Abrir PowerShell en la carpeta donde se guardara el proyecto, con Docker iniciado:

```powershell
git clone https://github.com/Adrian123CRff/Redolog.git
cd Redolog
powershell -ExecutionPolicy Bypass -File .\scripts\preparar-entorno.ps1 -Iniciar
```

Si ya tienen el repositorio, no necesitan clonarlo de nuevo. Actualizarlo antes de
preparar el entorno, conservando o confirmando sus cambios locales.

La primera instalacion descarga Oracle, crea rman-lab con almacenamiento
persistente, prepara los datos y compila la aplicacion. Puede tardar varios
minutos. Esperar el mensaje `Gestor RMAN disponible` y abrir
http://127.0.0.1:8787/ en el navegador del mismo equipo.

El instalador de Windows usa esta imagen inmutable, identificada en el
laboratorio probado el 04/10/2026:

```text
container-registry.oracle.com/database/free@sha256:8a8084193724b95bc62247e3af4218b357e4f172c78925551b2181dbe2566232
```

Los contenedores nuevos usan esa imagen. Los existentes de otra imagen no se
migran ni reemplazan automaticamente. El parametro avanzado `-Imagen` permite
una eleccion explicita distinta, pero deja de asegurar la version acordada.

## Comprobar que funciona

1. En Bases de datos, pulsar Comprobar conexion sobre el laboratorio.
2. Confirmar Oracle y RMAN disponibles y modo ARCHIVELOG.
3. Confirmar el tablespace FREEPDB1:LAB_DATOS. El script inicial crea
   LAB_DEMO.PEDIDOS y agrega tres pedidos de ejemplo si esos IDs no existen.
4. Crear una estrategia pequena, revisar el script y aprobar antes de ejecutar.
5. Tras el respaldo, comprobar piezas y verificacion; un codigo de salida cero
   no es por si solo prueba de recuperabilidad.

Los numeros de datafiles y las fechas pueden diferir entre equipos. Seleccionar
los objetos del diagnostico local; no copiar IDs de otra instalacion a ciegas.
Los objetos QA de pruebas anteriores de Adrian no se distribuyen con Git.

## Iniciar otro dia

Desde la raiz del repositorio, con Docker Desktop abierto:

```powershell
docker start rman-lab
java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"
```

Y en otra terminal, el Ejecutor, que es quien corre los horarios leyendo el catalogo:

```powershell
java -cp "target/gestor-rman-0.1.0.jar" edu.respaldos.Ejecutor
```

Esperar a que Oracle este listo antes de ejecutar respaldos. La terminal del
Ejecutor debe permanecer abierta para ejecutar los horarios; Ctrl+C detiene el
programa, no el contenedor Oracle. No iniciar dos veces la aplicacion ni el
Ejecutor sobre el mismo catalogo.

## Correo de alertas al DBA

Las variables de entorno y el correo del DBA son propios de cada equipo: Git no los
comparte. Para recibir avisos de respaldos fallidos, en la misma terminal de
PowerShell donde se inicia Java:

```powershell
$env:GESTOR_SMTP_HOST = "smtp.gmail.com"
$env:GESTOR_SMTP_USER = "cuenta@gmail.com"
$env:GESTOR_SMTP_PASSWORD = "contrasena-de-aplicacion"
java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.port=8787" -jar "target/gestor-rman-0.1.0.jar"
```

Opcionales: `GESTOR_SMTP_PORT` (587), `GESTOR_SMTP_FROM` (el usuario) y
`GESTOR_SMTP_STARTTLS` (true). No escribir la contrasena en archivos del
repositorio. Luego, en Bases de datos, registrar el "Correo del DBA" y usar
"Enviar correo de prueba". Sin `GESTOR_SMTP_HOST` el correo queda desactivado.

## Modo robot (sin interfaz web)

Para que se ejecuten los horarios sin abrir el navegador, iniciar Java con
`--robot` al final del mismo comando (con o sin las variables SMTP):

```powershell
java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" -jar "target/gestor-rman-0.1.0.jar" --robot
```

No abre el puerto 8787. El robot y la interfaz comparten el catalogo, por lo que
no pueden estar abiertos a la vez: detener uno antes de iniciar el otro. Para
dejarlo en segundo plano se puede crear una tarea del Programador de tareas de
Windows ("Al iniciar sesion") que ejecute ese comando desde la carpeta del proyecto.

## Compartir cambios mediante Git

El codigo y los scripts SQL se comparten; la base de datos no se sincroniza al
hacer `git pull`. Cada integrante trabaja en su rama y propone sus cambios para
integrarlos en master.

Para actualizar master, primero terminar los respaldos activos y detener la
aplicacion. Guardar o confirmar los cambios propios antes de cambiar de rama:

```powershell
git switch master
git pull --ff-only
.\mvnw.cmd package
```

Despues iniciar Java con el comando anterior. Si Git informa cambios locales o
ramas divergentes, revisar el caso; no usar reset --hard para forzar la actualizacion.

Los cambios nuevos de esquema o datos comunes deben entregarse como scripts SQL
versionados y documentar cuando ejecutarlos. Actualizar Git o compilar no ejecuta
automaticamente migraciones de base de datos.

## Que se conserva y donde

| Elemento | Almacenamiento | Se comparte en Git |
| --- | --- | --- |
| Codigo, pruebas y SQL inicial | Repositorio | Si |
| Datos de Oracle y archived logs | Volumen Docker rman-lab-data | No |
| Piezas de respaldo | runtime/backups | No |
| Estrategias, aprobaciones, historial y registros de la aplicacion | runtime | No |
| Clave inicial protegida con DPAPI | runtime/oracle-password.dpapi | No |

El instalador reutiliza el laboratorio compatible. El SQL inicial no borra filas
ni reemplaza pedidos existentes. La misma semilla no significa que las bases
permanezcan identicas despues de trabajar.

No subir runtime, archivos de Oracle, claves o respaldos a GitHub. No copiar un
catalogo H2 abierto entre equipos ni eliminar volumenes para resolver un error.
Si hay un volumen sin contenedor o una imagen distinta, el instalador se detiene
para que se revise como conservar o recuperar esos datos.

## Comprobaciones opcionales

Las pruebas Java no requieren Oracle real:

```powershell
.\mvnw.cmd test
```

Con Oracle y la aplicacion iniciados, espacio disponible y sin respaldos activos,
la prueba local crea una estrategia QA, respalda y verifica, y prueba un horario:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\lab\prueba-flujo-local.ps1 -Method LEVEL0
```

Genera respaldos reales y conserva archivos; no es un paso obligatorio de cada
arranque. No ejecutar scripts de recuperacion destructiva para instalar el entorno.

La imagen y el arranque Java se comprobaron en el equipo actual. La instalacion
completa desde cero en las computadoras de los demas integrantes queda por
verificar; sus recursos, permisos y descargas pueden producir diferencias.

Los ocho controles aislados del instalador pasaron en Windows PowerShell 5.1:
Java antiguo, Docker no disponible, motor Windows, contenedor ajeno, imagen
distinta, volumen sin contenedor, laboratorio compatible y descarga de la imagen
fijada solicitada por una instalacion nueva. Usan comandos sustitutos y se detienen
antes de modificar Oracle; no equivalen a ocho instalaciones completas.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\lab\prueba-instalador-windows.ps1
```
