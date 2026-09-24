# Despliegue

## Por que no Vercel

Vercel publica sitios estaticos y funciones que responden una peticion y terminan.
Esta aplicacion necesita cosas que Vercel no ofrece:

- un servidor Java que corre todo el tiempo;
- un planificador (Quartz) que lanza respaldos a su hora;
- un catalogo H2 en disco;
- Docker con Oracle y RMAN en la misma maquina (unos 3 GB de RAM).

Ningun hosting gratuito de aplicaciones da esa memoria ni permite Docker dentro del
servicio. Por eso hay dos formas de mostrar el proyecto.

| Opcion | Que muestra | Costo |
| --- | --- | --- |
| Render, modo simulacion | Toda la herramienta; RMAN simulado y senalado en pantalla | Gratis |
| Maquina virtual en Oracle Cloud (Always Free) | La version real con Oracle y RMAN | Gratis; pide tarjeta para verificar la cuenta |

## Opcion 1: demostracion publica en Render (recomendada)

El repositorio ya tiene lo necesario: `Dockerfile` (compila con Java 21 y arranca
con `GESTOR_MODO=simulacion`) y `render.yaml` (servicio web gratuito).

1. Sube el proyecto a GitHub.
2. Entra a https://render.com y crea una cuenta con "Sign in with GitHub".
3. New > Blueprint, elige el repositorio y la rama donde esta el codigo, y pulsa
   "Apply". Render lee `render.yaml`.
4. La primera compilacion tarda de 5 a 10 minutos. Al terminar queda una direccion
   como `https://gestor-respaldos-rman.onrender.com`.

Limitaciones del plan gratuito:

- Tras 15 minutos sin visitas el servicio se suspende. La siguiente visita tarda
  cerca de un minuto en despertarlo.
- El disco no es persistente: en cada reinicio la demostracion vuelve a los datos
  de ejemplo. Para una demostracion es una ventaja, porque siempre empieza limpia.

Protecciones del modo simulacion:

- Nunca ejecuta `docker` ni RMAN.
- Rechaza peticiones de otros sitios (verifica el Origin).
- Limita cuantas bases (10) y estrategias (30) puede crear un visitante.

Para probar la imagen en tu equipo antes de publicarla:

    docker build -t gestor-rman-demo .
    docker run --rm -p 8790:8787 gestor-rman-demo

y abre http://localhost:8790.

## Opcion 2: version real en Oracle Cloud Always Free

Sirve si el profesor pide ver la herramienta real funcionando fuera del equipo del
grupo.

1. Crea una cuenta en https://www.oracle.com/cloud/free/ y una instancia "Always
   Free" Ampere A1 (ARM, hasta 4 OCPU y 24 GB de RAM) con Oracle Linux o Ubuntu.
2. En la instancia instala Docker, Git y un JDK 21. Clona el repositorio y ejecuta
   `./scripts/preparar-entorno.sh`.
3. Inicia la aplicacion con `java -jar target/gestor-rman-0.1.0.jar`. En modo real
   solo escucha en 127.0.0.1, a proposito, porque ejecuta RMAN. Desde tu equipo
   entra con un tunel SSH:

       ssh -L 8787:127.0.0.1:8787 usuario@IP_DE_LA_INSTANCIA

   y abre http://127.0.0.1:8787.

No abras el puerto 8787 a internet en modo real: cualquiera podria lanzar
respaldos sobre la base.
