# Pruebas ampliadas: pausa solicitada por el usuario

Actualizacion 04/10/2026: el usuario solicito reanudar. Se iniciaron Oracle y la
aplicacion local, ambos estaban detenidos. La nueva pasada completa guarda sus
registros en runtime/pruebas-ampliadas/20261004161551. El resto de este documento
conserva el punto de pausa anterior; no es el estado final de la nueva pasada.
La reanudacion termino correctamente: 51 comprobaciones ampliadas y 10 de
recomendaciones aprobadas. Consultar pruebas-ampliadas-2026-10-04.md para el
resultado final, las recuperaciones y los limites de la validacion.

El usuario pidio detener las pruebas para ausentarse. No reanudar hasta que lo
solicite. No hay reanudacion automatica ni tareas programadas de QA.

## Estado confirmado al pausar
- La secuencia PowerShell fue detenida, sin detener Java ni Docker/Oracle.
- El respaldo ya despachado termino EXITOSO y verificado antes del cierre.
- Oracle READ WRITE; LAB_DATOS y ambos tablespaces QA ONLINE.
- Todas las estrategias QA estan desactivadas y sin programacion.
- No se ejecuto aun ningun RESTORE/RECOVER de la prueba ampliada.
- No se borraron piezas, objetos, archivos originales ni datos del usuario.

## Completado
- Suite ampliada: 41 pruebas automatizadas, cero fallos y errores.
- Copia DATABASE FULL comprimida, siete piezas, 510.04 MiB, verificada:
  93c8fbeb-665a-4e1a-9c9e-e6dedf68c6af.
- Bloqueos reales de concurrencia, edicion y liberacion durante una ejecucion.
- Rechazos de datafile inexistente, VALIDATE sin respaldo, operacion invalida y
  ruta con traversal.
- Nivel 0 del tablespace QA, exitoso y verificado:
  5f9b72f8-11db-470e-85f3-895bf921965b.
- Nivel 1 diferencial sin compresion del datafile 18, exitoso y verificado:
  79d06638-93c7-49be-8f36-e0038617f548.

## Evidencia y reanudacion
Primera pasada: runtime/pruebas-ampliadas/20260928194704.
Se corrigio un error del script PowerShell al serializar listas de un elemento;
no fue un fallo de RMAN. La copia completa de esa pasada se reutilizo.

Segunda pasada: runtime/pruebas-ampliadas/20260928195637.
El archivo estado-al-pausar.json conserva el estado y la evidencia del nivel 1.
Al detener el proceso no se ejecuto su finally: no asumir que resultados.json o
tamanos.json contienen el cierre completo de esta segunda pasada.

Objetos propios: QA_RMAN_20260928194704 y QA_RMAN_20260928195637. El segundo
contiene EVENTOS con las marcas 1 y 2, y usa el datafile 18. No desconectar
LAB_DATOS. Consultar el estado actual de Oracle y de la aplicacion antes de seguir.

Pendientes: recuperacion diferencial con cambio posterior, respaldo acumulativo
y recuperacion, componentes por separado y RESTORE DATABASE VALIDATE. Tambien
consolidar el informe y los tamanos finales. No presentar esta pausa como una
validacion completa ni como recuperacion de toda la instancia.

El script scripts/lab/prueba-ampliada.ps1 crea objetos QA nuevos en cada ejecucion.
No reanuda automaticamente desde el ultimo paso. CompletedFullBackupId permite
reutilizar una copia completa ya comprobada, pero no omite el resto de la cadena.
