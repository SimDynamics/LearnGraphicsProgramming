# Transporte libre de segmentos en Android

`math_segment_transport` es un control de respuesta de un `math_puzzle`, o un
bloque independiente, con validador `segment_transport`. Usa la misma geometría
que la construcción Windows: fuentes con longitud positiva, un origen y una
semirrecta dirigida para cadenas, o los dos extremos de un total para particiones.
La interfaz no muestra coordenadas, ejes, cuadrícula ni una lista de posiciones
correctas. Las figuras y la herramienta comparten una escala.

## Contrato

En `mode: "chain"`, `sources` declara segmentos con `id`, `from` y `to`. Cada extremo
contiene `x`, `y` y `label`; las posiciones son datos internos del dibujo.
`target.origin` tiene ese mismo formato; `target.direction` contiene `x` y `y` de
un vector no nulo. `steps` enumera los IDs de las fuentes, en el orden en que deben
transportarse; repetir un ID conserva la misma longitud. `pointLabels` contiene
un nombre distinto por extremo que construye el estudiante. El origen se da y no
se repite en la respuesta.

En `mode: "partition"`, `target.origin` y `target.end` delimitan el segmento total.
`partitionCount` indica el número de partes congruentes, entre 2 y 32. Se
construyen únicamente sus marcas interiores: `pointLabels` contiene exactamente
`partitionCount - 1` nombres. Para el punto medio, el número de partes es dos.

Ambos modos requieren `positionTolerance` y `lengthTolerance`: positivas,
finitas y menores que la cuarta parte de cualquier tramo requerido. Una
configuración degenerada, incompatible o fuera de los límites admitidos se
rechaza antes de pedir una respuesta. `initial.points`, opcional, es un prefijo
ordenado de puntos del borrador inicial; su ausencia deja la construcción vacía.
No se calcula ni dibuja una solución inicial.

`SegmentTransportSpec` conserva los límites de autoría del componente Windows:
coordenadas hasta `1e9` en valor absoluto, longitudes desde `1e-9`, total hasta
`1e9`, hasta 32 fuentes/tramos/partes y separación relativa hasta `1e10`. Las
tolerancias deben superar ocho unidades de precisión de `Double` en las
posiciones pertinentes. Estas condiciones controlan la posibilidad técnica de
representar el problema y no cambian sus condiciones geométricas.

## Controles

El selector de modo ofrece «Colocar puntos», «Mover herramienta», «Girar
herramienta» y «Vista». Un dedo coloca o arrastra el punto seleccionado, mueve la
apertura completa, la gira sin estirarla o desplaza la cámara según el modo.
El selector «Punto» contiene nombres de puntos existentes y del siguiente que se
puede construir. No contiene posiciones respuesta.

«Longitud» selecciona una fuente o un tramo ya construido. «Tomar longitud»
captura su apertura actual; corregir posteriormente ese tramo no estira la
herramienta capturada. «Acercar» y «Alejar» modifican la cámara y permiten llegar
fuera del encuadre inicial. Los controles para restablecer la vista o la
herramienta tampoco colocan puntos. «Restablecer construcción» vuelve a los
puntos iniciales mientras la respuesta sigue editable.

El dibujo y sus controles se amplían juntos mediante el botón de ampliación. El
panel de controles se desplaza si el espacio es pequeño. Los toques repetidos
sobre la superficie conservan su función de colocación. Las etiquetas se miden
con `Paint` real y usan el motor euclidiano existente para líneas, semirrectas,
flechas y nombres. Un fallo al actualizar conserva el último estado válido y
muestra un aviso técnico neutro, incluso si aún no existe un dibujo anterior.

## Evaluación y conservación

Se evalúan por separado pertenencia, sentido, orden, longitud de cada parte y
cobertura del total. Un extremo final correcto no compensa deformar o solapar
partes. La guía identifica la condición que requiere revisión sin entregar las
posiciones correctas. Una construcción incompleta o un fallo técnico produce un
resultado no evaluable y conserva la política ordinaria de intentos.

La respuesta utiliza el codec existente `{"type":"graph","points":[...]}`.
El orden corresponde a `pointLabels`. `responseDrafts` guarda además
`interactionDraft` con cámara, modo, punto seleccionado y herramienta. Estos
datos no participan en la identidad que se califica. El callback de borrador
conserva la navegación sin borrar feedback, habilitar otra evaluación ni
modificar la respuesta. Un borrador dañado no puede reemplazar los puntos
restaurados por separado. La jerarquía nativa conserva también el estado al
recrearse la vista.

Tras acertar quedan bloqueados los puntos; la herramienta y la cámara siguen
disponibles para examinar la construcción. Su borrador continúa guardándose sin
revocar el acierto. Reiniciar la actividad conserva su comportamiento ordinario.

Las cuatro lecciones españolas de segmentos y ángulos incorporan las prácticas
libres mediante mapas `stateLayout` v2 formados con sus fuentes Android. Los
bloques históricos mantienen sus direcciones, identificadores, respuestas y
contenido; los añadidos tienen identidades y almacenamiento independientes.

## Pruebas

`SegmentTransportTest` comprueba contratos, ambas familias de construcción,
rechazos, respuestas incompletas, codec, conservación de la apertura, cámara y
reapertura, y carga las doce construcciones publicadas con el parser y los mapas
Android. `MathSegmentTransportDeviceTest` cubre callbacks de respuesta/borrador,
congelación tras el acierto, restauración, borradores dañados y dibujos a dos
anchos. `ExerciseAttemptsDeviceTest` recorre los tipos registrados, incluidos
transporte de segmentos y copia rígida de ángulos, mediante sus superficies
nativas y conserva el ciclo de errores repetidos, acierto y reservas.

Las pruebas JVM y la compilación contra APIs Android no sustituyen ejecutar las
pruebas instrumentadas en un dispositivo. La inspección visual y la escucha se
realizan durante el estudio conforme al reparto indicado por el usuario.
