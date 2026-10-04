# Guia del avance presencial

Indicacion comunicada por el profesor: demostracion presencial durante el horario
normal de clase. No se ha indicado una duracion exacta ni un formato obligatorio
de diapositivas. Esto no cambia el horario de las estrategias de respaldo.

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
