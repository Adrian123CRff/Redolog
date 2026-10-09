# Guia del avance presencial

Indicacion comunicada por el profesor: demostracion presencial durante el horario
normal de clase, presentando el avance como un producto que se ofrece a un cliente.
El grupo estima unos 15 minutos y cuatro participantes, con el cuarto por confirmar.
La distribucion siguiente es una propuesta de ensayo, no una rubrica del profesor.
No se ha indicado un formato obligatorio de diapositivas. Esto no cambia el
horario de las estrategias de respaldo.

## Mensaje central

"El Gestor de Respaldos Oracle convierte la ejecucion de RMAN en un proceso
planificado, aprobado y trazable, con evidencia y alertas para el administrador."

Publico objetivo de la propuesta: un administrador responsable de proteger una
base Oracle. Vender el valor significa relacionar problema, beneficio y prueba;
no inventar ahorros, clientes, garantias de recuperacion ni funciones pendientes.

## Distribucion para cuatro personas

| Tiempo acumulado | Responsable | Objetivo | Apoyo visual |
| --- | --- | --- | --- |
| 0:00-2:00 | Persona 1 | Problema, usuario objetivo y propuesta de valor | Nombre del producto y Monitor |
| 2:00-6:00 | Persona 2 | Explicar la estrategia: que, como, cuando y destino | Constructor sobre LAB_DATOS |
| 6:00-10:00 | Persona 3 | Demostrar validacion, revision y autorizacion | Vista previa y error controlado sin guardar |
| 10:00-13:00 | Persona 4 | Evidencia real, prevencion, limites y cierre | Historial, verificacion y una alerta |
| 13:00-15:00 | Grupo | Preguntas o margen por transiciones | Informe de cumplimiento disponible |

El contenido principal dura 13 minutos y deja dos de margen dentro de los 15.
Si el profesor asigna preguntas fuera del tiempo, ese margen permite profundizar
en la evidencia; no es necesario agregar funciones o diapositivas de relleno.
Una sola persona maneja el equipo durante toda la demostracion, idealmente quien
conozca mejor el laboratorio. Los demas narran su parte y acuerdan cada cambio
de pantalla durante el ensayo.

## Guion orientativo

### Persona 1: por que alguien lo necesitaria

Apertura sugerida, como escenario hipotetico:

> Imaginen que una organizacion necesita recuperar sus pedidos y descubre que
> un respaldo no se ejecuto o que nunca se comprobo su contenido. El problema no
> es solamente crear una copia: es saber que se esta protegiendo y tener evidencia
> de lo que ocurrio. Nuestro producto ayuda al administrador a organizar ese proceso.

Presentar el nombre del producto y tres beneficios: planificacion, trazabilidad
y deteccion de riesgos. No comenzar enumerando lenguajes y herramientas.
Relacionar disponibilidad con volver a tener los datos accesibles e integridad
con conservar y recuperar informacion correcta, sin prometer que el programa
garantice ambas por si solo.

Transicion:

> Veamos como un administrador define la proteccion de los pedidos del laboratorio.

### Persona 2: convertir la necesidad en una estrategia

Mostrar una estrategia pequena sobre FREEPDB1:LAB_DATOS. Explicar las decisiones,
no leer cada campo del formulario:

- Que: proteger los pedidos contenidos en ese tablespace.
- Como: nivel 0 como base de una estrategia incremental; FULL no es equivalente.
- Componentes: archived logs, control file y SPFILE aportan elementos necesarios
  para distintos escenarios de recuperacion; no bastan para garantizar cualquier escenario.
- Cuando: fecha, horario o intervalo. La prioridad orienta el objetivo de frecuencia.
- Donde: destino de disco y espacio disponible, con estimacion limitada para componentes.

Frase de valor:

> El administrador transforma una necesidad de proteccion en una politica visible.
> No tiene que depender solamente de comandos aislados o de recordar cada ejecucion.

Dedicar una frase a la arquitectura: Java coordina, Quartz planifica y RMAN
ejecuta dentro del Oracle del laboratorio Docker. La nube no es parte de la
demostracion actual. La aplicacion debe permanecer encendida para los horarios.

Transicion:

> Antes de ejecutar esa politica, mostramos que instrucciones producira y comprobamos
> si la configuracion es valida.

### Persona 3: demostrar el control, no solo describirlo

La interaccion con la aplicacion se realiza en vivo. Para el caso de error,
usar un borrador nuevo, sin modificar ni guardar una estrategia ya probada:

1. Mostrar el script de una configuracion valida y relacionar BACKUP, alcance y destino.
2. En un borrador con alcance DATAFILE, introducir un numero confirmado como
   inexistente en el diagnostico local (65533 fue el usado en nuestras pruebas).
3. Mostrar el error y la ausencia de script y huella aprobable.
4. Cancelar el borrador. Explicar que no se ejecuto RMAN ni se modificaron datos.
5. Mostrar la revision de una estrategia valida y explicar la aprobacion por huella:
   cambiar la configuracion exige revisar la nueva version antes de ejecutar.

Frase de valor:

> No esperamos a lanzar el respaldo para descubrir este error. El administrador
> puede corregir la configuracion antes de autorizarla y revisar las instrucciones.

No es necesario crear un horario ni lanzar un respaldo nuevo solo para llenar
estos cuatro minutos. Si el profesor exige ejecucion en vivo, usar la variante
indicada mas abajo, con una estrategia pequena preparada y capacidad comprobada.

Transicion:

> Ahora veamos las ejecuciones reales que ya realizamos y como comprobamos sus resultados.

### Persona 4: demostrar resultados y cerrar la propuesta

Abrir un respaldo real del historial identificado en la tabla de evidencia de
esta guia. Decir expresamente que se ejecuto el 04/10/2026: no presentarlo como
si acabara de ocurrir durante la exposicion.

Mostrar resultado, piezas y VALIDATE BACKUPSET de esa misma copia. Abrir tambien
el registro con origen HORARIO para evidenciar el disparo de Quartz. Explicar
una alerta y una recomendacion aplicada por decision humana. No recorrer todo
el historial ni leer registros RMAN completos.

> El resultado no se reduce a un mensaje de exito: conservamos instrucciones,
> archivos identificados y evidencia de verificacion. Esto permite al administrador
> revisar lo ocurrido y atender condiciones que requieren una decision.

Cerrar diferenciando avance y producto de produccion:

> Nuestro aporte no es reemplazar RMAN, sino gestionarlo mediante un flujo de
> planificacion, aprobacion, automatizacion y evidencia. El avance funciona en un
> laboratorio local y tiene pruebas reales. La verificacion de piezas no sustituye
> una recuperacion completa; tampoco estamos prometiendo proteccion ante cualquier
> desastre. Queremos validar con ustedes que el alcance y la interpretacion de la
> ventana de respaldo corresponden a lo esperado para la siguiente entrega.

## Si finalmente exponen tres

| Tiempo acumulado | Responsable | Contenido |
| --- | --- | --- |
| 0:00-3:00 | Persona 1 | Problema, propuesta y arquitectura en una frase |
| 3:00-8:00 | Persona 2 | Estrategia, script, error controlado y aprobacion |
| 8:00-13:00 | Persona 3 | Evidencia manual y automatica, alerta, limites y cierre |
| 13:00-15:00 | Grupo | Preguntas o margen |

No se agrega una cuarta parte a ultima hora: se conserva la misma historia y
se reduce el recorrido de campos. Los nombres concretos se asignan cuando el
grupo confirme quienes participan.

## Variante con respaldo en vivo

Solo usarla si el profesor lo pide o si el ensayo confirma que cabe en el tiempo.
Antes de clase, dejar una estrategia pequena, sin horarios, con alcance y destino
revisados. Tener espacio suficiente y ningun trabajo activo. La persona 3 muestra
la revision, obtiene la aprobacion y lanza el respaldo manual. Mientras se ejecuta,
la persona 4 explica las alertas y la evidencia anterior, identificandola como tal.

Si termina, abrir su resultado real y verificacion. Si sigue ejecutandose al
cerrar la exposicion, decirlo y no anunciar exito anticipado. Si falla, mostrar
el estado y el error, sin relanzarlo repetidamente ni ocultarlo con un registro
anterior. No hacer recuperaciones destructivas ni respaldos completos largos.

## Preguntas que conviene ensayar

| Pregunta | Respuesta breve y defendible |
| --- | --- |
| Por que no usar solo RMAN o un script | RMAN es el motor; la aplicacion agrega gestion de estrategias, aprobacion, horarios, evidencia y controles preventivos |
| Como saben que un respaldo sirve | Comprueban archivos, identifican conjuntos y los leen con VALIDATE BACKUPSET; ademas hubo recuperaciones QA controladas, sin afirmar recuperabilidad universal |
| Que pasa si se apaga la aplicacion | Los horarios no se ejecutan mientras esta apagada; al volver, el control de omisiones evalua las ocurrencias pertinentes, no repite automaticamente todo lo perdido |
| Que significa ventana | Duracion maxima advertida, incluida la verificacion posterior; no franja de inicio/fin ni interrupcion automatica de RMAN |
| Ya funciona para todos los integrantes | Hay instalador Windows y version de imagen fijada; cada integrante tiene datos propios. No afirmar instalaciones completas que aun no se hayan comprobado |
| Esta listo para produccion | No; es un avance funcional de laboratorio con pruebas y limites documentados |

## Ensayo del grupo

- Hacer un ensayo con cronometro y la misma computadora de la demostracion.
- Ensayar los cambios de pantalla y las frases de transicion, no memorizar todo.
- Acordar quien responde preguntas sobre RMAN, arquitectura y pruebas.
- Al minuto 10 pasar a evidencia; al minuto 12 iniciar el cierre.
- Ante una interrupcion, conservar problema, un flujo completo y evidencia;
  recortar la enumeracion de campos o tecnologias.
- Llevar capturas y registros identificados como respaldo de la explicacion.
  Si la aplicacion no abre, informar la limitacion: una captura no es una ejecucion en vivo.

## Antes de clase

- Llevar el equipo con Docker, el contenedor rman-lab y la aplicacion disponibles.
- Comprobar Oracle y RMAN desde la aplicacion y revisar el espacio libre.
- Abrir http://127.0.0.1:8787/ en el mismo equipo; esa direccion no es publica.
- Tener abiertos el informe de cumplimiento y las evidencias locales. Los datos,
  respaldos y registros de runtime no se suben al repositorio Git.
- Mantener desactivados los horarios QA. No iniciar respaldos completos ni
  recuperaciones destructivas como preparacion de la exposicion.

## Secuencia de demostracion

1. **Problema y objetivo.** Explicar que el programa organiza estrategias de
   respaldo y reduce olvidos y copias no comprobadas. Distinguir disponibilidad
   de integridad.
2. **Base de datos.** Mostrar la conexion al laboratorio Oracle en Docker, el
   modo ARCHIVELOG y el destino. RMAN ejecuta los respaldos; Java coordina el flujo.
3. **Que y como.** Abrir una estrategia sobre FREEPDB1:LAB_DATOS, explicar
   prioridad, nivel 0, compresion y componentes. Un FULL no equivale a nivel 0.
4. **Cuando.** Mostrar fecha, horas, dias o intervalo. Explicar que Quartz necesita
   la aplicacion encendida. La ventana actual es una duracion maxima advertida,
   no una franja de inicio y fin; confirmar esa interpretacion con el profesor.
5. **Validacion y aprobacion.** Relacionar los campos con el script generado.
   Mostrar que un datafile inexistente bloquea la vista previa, sin guardar ese
   borrador. Explicar la huella aprobada y por que editar exige revisar de nuevo.
6. **Ejecucion y evidencia.** Abrir las ejecuciones indicadas abajo, mostrar origen,
   tiempos, piezas, conjuntos y VALIDATE BACKUPSET. La lectura de piezas no
   sustituye una recuperacion real.
7. **Prevencion.** Mostrar una estrategia inactiva y una recomendacion aplicada
   por decision humana. Explicar que una advertencia de horario no invalida una
   copia que ya se verifico.
8. **Estado del avance.** Resumir pruebas superadas, limites y siguientes pasos.
   No afirmar recuperacion ante cualquier desastre ni preparacion para produccion.

## Evidencia concreta disponible en este equipo

| Caso | Ejecucion | Resultado del 04/10/2026 |
| --- | --- | --- |
| Nivel 0 manual | 94f0ff2a-4502-446d-bc16-25e2157679b4 | Exitoso y verificado |
| Verificacion manual de ese respaldo | 9fa9de4d-451a-4027-8a5e-0002791031ec | Exitosa y vinculada a la copia correcta |
| Nivel 0 por horario | 10d62fb0-4e8d-4381-9be9-a3a32ab6fe8a | Disparo automatico a las 17:42, America/Guatemala; exitoso y verificado |

La estrategia QA_LOCAL_20261004-173857 quedo desactivada al terminar. Los
resultados del avance son 56 pruebas automatizadas, 23 comprobaciones locales y
13 de navegador. No se deben sumar como si todas fueran respaldos reales.

Para una demostracion corta, usar primero estas ejecuciones identificadas. Si el
profesor solicita una ejecucion en vivo, revisar y aprobar una estrategia pequena
de laboratorio, comprobar capacidad y esperar su resultado real. No prometer un
tiempo exacto basandose en las pruebas anteriores. Al terminar, desactivar cualquier
horario creado para la demostracion.

## Material de apoyo

- [Revision actualizada de los puntos 1-16](correcciones-revision-enunciado-2026-10-04.md).
- [Analisis y diseno](analisis-y-diseno.md).
- [Recuperaciones controladas y pruebas ampliadas anteriores](evidencias/pruebas-ampliadas-2026-10-04.md).
- Paquete local de seis evidencias: runtime/entrega-evidencias/20261004-172121.

El formato final y el ensayo de la exposicion siguen pendientes; esta guia no
representa una presentacion ya realizada ni una aprobacion del profesor.
