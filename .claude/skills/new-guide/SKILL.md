---
name: new-guide
description: Anade una guia de composicion nueva al motor. Usala cuando se pida una guia, rejilla, overlay o patron de encuadre nuevo.
allowed-tools: Read, Grep, Glob, Edit, Write, Bash
---

# Anadir una guia de composicion

Seis pasos. El quinto es el que siempre se olvida.

## 1. Declarar la guia

En `core/src/commonMain/kotlin/dev/propor/core/domain/guide/Guide.kt`:

- anade el caso al enum `GuideKind`, con un KDoc que diga **para que sirve**, no como se dibuja
- si entra en el alcance actual, anadela a `GuideKind.R1`
- decide si `encouragesCentering` debe devolver true para ella

Ese ultimo punto importa mas de lo que parece: es lo que evita que el coach avise de "sujeto
centrado" a quien eligio una guia que precisamente pide centrar. Es una propiedad semantica y no
se puede deducir de la geometria.

## 2. Implementar la geometria

En `GuideGeometryFactory.geometryFor`, anade la rama. Reglas:

- coordenadas **normalizadas 0..1**, nunca pixeles
- si la guia depende de la forma del rectangulo (perpendiculares, espirales), calcula en el
  espacio fisico usando `aspect.ratio` y convierte al final. Calcularlo directo en normalizado
  da lineas que no son perpendiculares en pantalla
- `segments` para rectas, `curves` para polilineas ya teseladas, `anchors` para los puntos
  donde el coach sugiere colocar el sujeto
- sin anclas la guia es pura referencia; con anclas es una opinion

## 3. Tests de tabla

En `GuideGeometryFactoryTest`, con los cuatro formatos y las dos orientaciones. Como minimo:

- la guia no queda vacia en ninguno de los ocho casos
- una comprobacion **analitica** de al menos un valor: un caso donde el resultado correcto se
  pueda calcular a mano. Sin eso, el test solo confirma que el codigo hace lo que hace

Ejecuta: `./gradlew :core:jvmTest`

## 4. Icono en el sistema de diseno

Vectorial, en ambas plataformas, con el mismo trazo que las demas.

## 5. Cadenas en es, en y pt

**Este es el paso que se olvida.** Y no es solo el nombre: hace falta tambien la explicacion
corta de para que sirve la guia, porque el selector se lee mirando, no leyendo nombres.

Recuerda que el dominio no lleva texto (ADR-004): las cadenas viven en la capa de presentacion.

## 6. Miniatura en el selector

Con su snapshot en el catalogo de componentes. Si no esta en el catalogo, no esta terminada.

---

Al terminar, deja constancia en la tarjeta del tablero `PCA` (MCP `foundry-boards`): que guia se
anadio, en que formatos se comprobo y cualquier decision de geometria que haya requerido criterio.
