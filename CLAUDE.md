# PROPOR

Camara asistente de composicion fotografica. La promesa: *«la camara que te ensena a ver».*

iOS (SwiftUI + AVFoundation) y Android (Compose + CameraX), con el nucleo compartido en Kotlin
Multiplatform. El blueprint completo vive en Foundry, area `propor-cam-app`, tablero `PCA`.

## Arquitectura — INNEGOCIABLE

Hexagonal. Las dependencias apuntan siempre hacia dentro.

- `core/` NO importa androidx, android, platform, java, javax, UI ni ningun framework.
- El dominio no importa de `application`. Los puertos de SALIDA viven en `domain/port`
  (quien los necesita es el dominio); en `application` van los puertos de ENTRADA, o sea los
  casos de uso.
- La presentacion solo conoce casos de uso, nunca adaptadores. Un adaptador nunca importa otro.
- Coordenadas **siempre normalizadas 0..1** en el dominio. Un pixel dentro de `core/` es un bug
  de arquitectura (ADR-003).
- `Advice` **no lleva texto de interfaz** (ADR-004). El dominio decide QUE esta mal; la
  presentacion decide COMO se dice y si se dice o solo se vibra.

Todo esto lo verifica `ArchitectureTest`, que rompe el build. No es documentacion: es un test.

## Comandos

```
./gradlew :core:jvmTest      tests del nucleo, incluido el guardian de arquitectura
./gradlew :core:allTests     todos los targets disponibles en este host
```

En Windows y Linux el nucleo se construye y prueba entero sobre la JVM. Los targets de Apple
solo se compilan en macOS: el `build.gradle.kts` de `core` los declara condicionalmente.

## Como esta organizado el coach

Dos clases, y la segunda importa mas que la primera:

- `AdviceEngine` decide **que** esta mal. Puro y determinista: misma entrada, misma salida.
- `AdviceThrottler` decide **cuando callarse**. Ahi esta el producto. La competencia habla casi
  siempre; nosotros nos callamos entre el 60 % y el 80 % del tiempo de visor, a proposito.

Si hay que recortar alcance, se recortan reglas del motor, nunca el afinado del throttler.

## Reglas de trabajo

- Toda tarjeta vive en Foundry (MCP `foundry-boards`, tablero `PCA`). Al terminar algo, dejar
  constancia ahi.
- Prohibido `TODO` sin id de tarjeta. Usa `TODO(PCA-42): ...`.
- Nada de numeros magicos en el dominio: constante con nombre y unidad.
- Prohibidos los sufijos `Manager`, `Helper`, `Util`, `Processor`. Si no hay nombre mejor, la
  clase hace demasiadas cosas.
- Tests de tabla para todo lo geometrico, en 4:3, 3:2, 16:9 y 1:1, horizontal y vertical.
- Ningun test del throttler puede usar `sleep()`: el reloj se inyecta con `ClockPort`.

## Estado actual

R1 en curso. Terminado y probado: geometria normalizada, las 10 guias de composicion,
`AdviceEngine` con sus 7 reglas, `AdviceThrottler` y el guardian de arquitectura.
Pendiente: adaptadores de camara y vision, y la capa de presentacion.
