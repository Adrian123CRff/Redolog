# Cambios pedidos en la clase del 05/10/2026

El profesor pidio ajustar la herramienta antes de la presentacion. Esta tabla dice
que pidio, que ya existia en la rama `kenny-correo-rma-robot` y que se agrego en
`profesor-catalogo-ejecutor`. Evalua funcionalidad; todos deben saber explicarla.

| Pedido | Estado | Donde esta |
| --- | --- | --- |
| Varios tablespaces a la vez | Ya existia | Constructor, paso 2: se marcan con clic |
| Control file, archived logs | Ya existia | Constructor, paso 2 |
| **Redo logs en linea** con check | Nuevo | Casilla "Redo logs en linea": agrega `SQL 'ALTER SYSTEM ARCHIVE LOG CURRENT'` y respalda el archived log resultante. RMAN no copia los redo en linea directamente |
| **Full** que marca todo / parcial | Nuevo | Boton "Full: marcar todo" (base completa + redo + archived logs + control file + SPFILE). Debajo se indica si el respaldo es Full o parcial |
| Completo, incremental | Ya existia | Paso 3: completo, nivel 0, nivel 1 diferencial y acumulativo |
| Prioridad de la estrategia | Ya existia | Paso 1 |
| **Prioridad de cada tablespace** | Nuevo | Paso 2, al elegir dos o mas tablespaces. El script respalda primero los de prioridad alta |
| Dias de la semana, horas | Ya existia | Paso 4: semanal con varios dias y varias horas |
| **Ciclica o no** | Nuevo | Paso 4: "Una vez (no ciclica)" corre solo en la fecha de inicio; las demas frecuencias se repiten |
| Sobre que base de datos | Ya existia | Paso 1 |
| **Script con nombre propio RMA000x.rma** | Nuevo | `runtime/scripts/RMA0001.rma`, `RMA0002.rma`... consecutivo por estrategia; se conserva al editar |
| **Catalogo en archivo plano** | Nuevo | `runtime/catalogo-estrategias.txt`, una linea por estrategia (codigo, script, dias, horas, prioridad, aprobada, huella, ultima ejecucion, resultado, piezas, log) |
| **Ver la estrategia en un modal** | Nuevo | Vista **Catalogo**: boton "Ver script .rma" y "Ver archivo plano" |
| **Ejecutor aparte** que lee el catalogo y llama a RMAN | Nuevo | Programa `edu.respaldos.Ejecutor` (ver abajo) |
| El ejecutor guarda **pieza y LOG** en el catalogo | Nuevo | Invoca `rman target / cmdfile=RMA0001.rma log=...` y actualiza ULTIMA_EJECUCION, RESULTADO, PIEZAS y LOG |
| **Ver el log** en un modal | Ya existia y se amplio | Ejecuciones: "Registro RMAN". Catalogo: "Ver log de la ultima ejecucion" |
| **Volver a correr** una estrategia que fallo | Nuevo | Boton "Volver a ejecutar ahora" en el detalle de una ejecucion fallida |
| Aviso al DBA del fallo | Ya existia | Correo automatico (tambien para lo que ejecuta el Ejecutor) |
| Tabla de 7 u 8 fallos de medio fisico | Nuevo | [fallos-medio-fisico.md](fallos-medio-fisico.md) |

## El ejecutor

El profesor describio dos programas: la aplicacion que **crea** estrategias y un
ejecutor que las **corre**. Asi queda:

1. La aplicacion genera `RMA000x.rma` y la linea del catalogo. Solo marca
   `APROBADA=SI` cuando el administrador aprobo ese script, y guarda su huella.
2. El ejecutor lee `runtime/catalogo-estrategias.txt` cada 20 segundos, linea por
   linea. Si una estrategia activa y aprobada toca en ese momento (mismo dia y hora,
   mismas reglas que la aplicacion), la ejecuta. Si varias tocan a la vez, va de
   prioridad alta a baja.
3. Antes de ejecutar compara la huella del `.rma` con la del catalogo: si alguien
   modifico el archivo despues de aprobarlo, no lo ejecuta.
4. Ejecuta RMAN en el contenedor con el script y pidiendole el log
   (`rman target / cmdfile=... log=...`), igual que `system("rman ... @RMA0001.rma")`.
5. Guarda el log en `runtime/ejecutor/logs/` y actualiza en el catalogo la ultima
   ejecucion, el resultado, las piezas y la ruta del log. Despues sigue con la
   siguiente linea y vuelve a empezar.
6. La aplicacion incorpora esos resultados a su historial: aparecen en Ejecuciones
   (origen "Ejecutor"), se ve el log en el modal y, si fallo, se avisa al DBA por correo.

### Como correrlo (Windows, desde la carpeta del proyecto)

Para que los horarios los ejecute solo el ejecutor, la aplicacion se inicia con el
planificador externo:

    java "-Djdk.net.unixdomain.tmpdir=runtime/app.lock" "-Dapp.mode=local" "-Dapp.planificador=externo" -jar target/gestor-rman-0.1.0.jar

Y en otra ventana de PowerShell:

    java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor

Para correr una estrategia de inmediato desde la terminal (por ejemplo, la que fallo):

    java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor --ahora RMA0001

Si la aplicacion se inicia como siempre (planificador interno), ella misma ejecuta
los horarios y el ejecutor se niega a arrancar para no respaldar dos veces lo mismo.
`--ahora` funciona en los dos modos.

## Como mostrarlo en la presentacion

1. Crear una estrategia con dos tablespaces de distinta prioridad, archived logs y
   control file, martes y viernes a las 02:00. Mostrar el script en vivo: un BACKUP
   por prioridad.
2. Aprobarla y abrir **Catalogo**: aparece su linea con `RMA000x.rma`. Abrir el
   script en el modal y el archivo plano completo.
3. Correr una estrategia con el ejecutor (`--ahora`) y mostrar que el catalogo se
   actualizo con la pieza y el log.
4. Ejecutar EST901 (destino sin permiso): falla con `ORA-19504`, llega el correo al
   DBA, se abre el log en el modal y se vuelve a ejecutar con el boton.
5. Abrir la tabla de fallos de medio fisico y explicar que estrategia cubre cada uno.
