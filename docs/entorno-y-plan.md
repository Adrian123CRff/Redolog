# Entorno encontrado y plan de trabajo

## Que vamos a desarrollar

La aplicacion del grupo se escribira en Java. Permitira configurar estrategias,
generar scripts RMAN, ejecutarlos por horario y consultar resultados y errores.
RMAN es el programa de Oracle que ejecuta los respaldos y la recuperacion.
Oracle Database es el servidor que guarda los datos. Docker ejecuta los entornos
Oracle locales dentro de contenedores Linux en este equipo Windows.

El profesor aun debe completar las indicaciones. Los requisitos pendientes
estan identificados en requisitos-proyecto.md.

## Resultado de la verificacion

| Componente | Hallazgo |
| --- | --- |
| Windows | Windows 11 Home, compilacion 26200, version 25H2 |
| Java | JDK 25.0.1 |
| Maven | 3.9.16 |
| Instalador descargado | Downloads/OracleXE213_Win64.zip |
| Instalacion nativa 21c | Intento fallido registrado; no se encontro una instancia nativa operativa |
| Docker Desktop | Motor operativo al finalizar el diagnostico |
| oradb | Contenedor iniciado y conexion SQL local comprobada |
| Version real de oradb | Oracle AI Database 26ai Free, 23.26.2.0.0 |
| CDB de oradb | FREE, READ WRITE, ARCHIVELOG |
| PDB de oradb | FREEPDB1 en MOUNTED al consultar; no se cambio su estado |
| RMAN en oradb | No existe en el ORACLE_HOME comprobado; imagen gvenzl/oracle-free:23-slim |
| Oracle-AdrianFer | Contenedor existente con imagen completa de Oracle; detenido |
| RMAN en imagen completa | 23.26.1.0.0; arranque y salida verificados en un contenedor temporal |

La etiqueta 23-slim no demuestra que la version instalada sea 21c ni permite
conocer el parche exacto. La version anterior se obtuvo de v$version.

El intento de iniciar Oracle-AdrianFer encontro un conflicto en el puerto 1521
con oradb. La prueba de RMAN se hizo usando la imagen completa sin red, sin
arrancar una base y sin publicar puertos. Ese contenedor temporal se elimino
automaticamente al salir. No se realizaron respaldos ni recuperaciones.

Imagen completa comprobada:

    sha256:8a8084193724b95bc62247e3af4218b357e4f172c78925551b2181dbe2566232

Ejecutable comprobado dentro de esa imagen:

    /opt/oracle/product/26ai/dbhomeFree/bin/rman

## Preparacion pendiente del laboratorio

1. Elegir una instancia con instalacion completa que incluya RMAN. Puede
   aprovecharse Oracle-AdrianFer o prepararse un laboratorio independiente.
2. Si se necesitan dos instancias a la vez, asignar puertos distintos en el
   equipo. El conflicto detectado es de puertos, no un fallo de los datos.
3. Comprobar version, estado y configuracion de esa instancia con su propio
   RMAN. No asumir que el ejecutable 23.26.1 es el adecuado para oradb 23.26.2.
4. Definir donde persistiran los datos y respaldos. oradb no tiene montajes
   declarados en Docker, por lo que no debe recrearse para cambiar su imagen.
5. Preparar datos de prueba y un destino de respaldo con espacio disponible.
6. Ejecutar el primer respaldo manual y guardar evidencia de su validacion.

Se midieron aproximadamente 28.4 GB libres en C: antes de iniciar las bases.
El espacio se debe volver a comprobar al definir el tamano del laboratorio.

## Incidencia de arranque de Docker

Docker no iniciaba por un archivo temporal de comunicacion inaccesible en
AppData/Local/Docker/run/dockerInference. Con Docker detenido se renombro la
carpeta run, que contenia dos sockets, a run-respaldo-20260922 dentro de Docker.
La carpeta original se conservo como respaldo. Un intento posterior mostro otro
socket bloqueado en docker-secrets-engine; el intento de renombrar esa segunda
carpeta se cancelo al detectar Docker ejecutandose y no la modifico.
Despues del arranque indicado por el usuario, el motor respondio correctamente.

## Organizacion propuesta para las dos semanas

| Periodo | Resultado esperado |
| --- | --- |
| Dias 1-2 | Laboratorio operativo, entender RMAN y ejecutar un respaldo manual |
| Dias 3-4 | Modelo de datos y formularios de bases, estrategias y horarios |
| Dias 5-6 | Generacion de scripts y ejecucion manual desde Java |
| Dias 7-9 | Planificador persistente, historial y control de duplicados |
| Dias 10-11 | Registro de fallos y alertas de correo en laboratorio |
| Dias 12-13 | Pruebas de restauracion/recuperacion y medicion de resultados |
| Dia 14 | Documentacion y ensayo de la demostracion |

Es una propuesta de distribucion del trabajo, no una fecha de entrega confirmada.
Las pruebas de recuperacion se realizaran sobre datos de laboratorio.

## Referencias

- [Oracle: inicio del cliente RMAN e instalacion con la base](https://docs.oracle.com/en/database/oracle/oracle-database/21/bradv/starting-interacting-with-rman-client.html)
- [Oracle: requisitos del instalador XE en Windows](https://docs.oracle.com/en/database/oracle/oracle-database/21/xeinw/installing-oracle-database-xe.html)
- [Imagenes gvenzl: diferencias entre slim, regular y full](https://github.com/gvenzl/oci-oracle-free/blob/main/README.md)
- [Oracle: compatibilidad de RMAN](https://docs.oracle.com/en/database/oracle/oracle-database/26/rcmrf/rman-compatibility.html)
