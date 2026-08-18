Read this in English: [README.en.md](README.en.md)

# Financial Transactions — Transaction Execution API

API REST para ejecutar y consultar transacciones financieras (CREDIT/DEBIT) contra un
proveedor externo, con idempotencia real, resiliencia en múltiples capas (timeouts,
reintentos, circuit breaker) y persistencia en PostgreSQL.

## Arquitectura

Arquitectura hexagonal híbrida en 5 paquetes bajo `com.financial.transactions.challenge`:

```
                        ┌──────────────┐
                        │  controller  │  HTTP ↔ dominio (DTOs, versionado, manejo de errores)
                        └──────┬───────┘
                               │ depende de
                        ┌──────▼───────┐
                        │   service    │  casos de uso (ExecuteTransactionService,
                        │  service/port│  QueryTransactionService) + interfaces de puerto
                        └──┬────────┬──┘
                 implementa│        │implementa
          ┌────────────────▼┐    ┌──▼─────────────┐
          │   repository     │    │    provider     │
          │ (JdbcClient +    │    │ (RestClient +   │
          │  PostgreSQL)     │    │  Resilience4j)  │
          └──────────────────┘    └─────────────────┘
                        ┌──────────────┐
                        │    domain    │  Transaction, Money, reglas de negocio,
                        │              │  excepciones — sin dependencias de framework
                        └──────────────┘
```

**Regla de dependencias**: `domain` no depende de nada. `service` depende solo de
`domain` y de sus propias interfaces (`service/port/TransactionRepository`,
`service/port/TransactionProvider`). `repository` y `provider` son adaptadores que
implementan esas interfaces — `service` nunca conoce JDBC, Postgres, RestClient ni
Resilience4j directamente. `controller` depende de `service`, nunca al revés.

## Decisiones de diseño

- **Tomcat + `ExecutorService` dedicado, no stack reactivo (WebFlux/R2DBC)**: el perfil
  I/O-bound del sistema (una llamada HTTP saliente por transacción) favorecería en teoría
  un stack reactivo por throughput con pocos threads. Se priorizó igualmente el patrón
  más común en producción fintech y más solicitado en procesos de entrevista, con curva
  de aprendizaje y debugging bastante más simple (stack traces lineales, sin operadores
  reactivos que rastrear).
- **`JdbcClient` con SQL explícito, no JPA/Hibernate**: control total sobre las consultas
  (incluyendo el `upsert` con `ON CONFLICT` que la idempotencia necesita) sin la capa de
  indirección de un ORM ni sorpresas de lazy-loading.
- **PostgreSQL + Flyway**: migraciones versionadas e inmutables (`V001`...`V003`) como
  fuente de verdad del esquema, reproducibles en cualquier ambiente.
- **Idempotencia real, no solo deduplicación**: el header `Idempotency-Key` es
  obligatorio. `EXECUTED` y `REJECTED` son estados terminales — el proveedor ya dio una
  respuesta de negocio definitiva, así que un reintento del cliente devuelve la
  transacción congelada sin tocar al proveedor. `FAILED` (nunca hubo respuesta real del
  proveedor) **no** es terminal: un reintento con la misma key vuelve a llamar al
  proveedor de verdad, actualizando el mismo registro vía `upsert` (`ON CONFLICT (id)`)
  — mismo `id`, mismo `created_at`, status y datos del proveedor actualizados.
- **Resiliencia en 4 capas**, todas externalizadas en `application.yml` (nada
  hardcodeado):
  1. **Socket** — connect/read timeout del `RestClient` (`app.provider.connect-timeout`,
     `app.provider.read-timeout`): el caso esperado de "proveedor lento".
  2. **`Future.get(timeout)`** sobre un `ExecutorService` dedicado
     (`app.provider-executor.future-timeout`): red de seguridad para cuelgues que no
     dependen del socket (DNS, contención del pool).
  3. **Retry con backoff exponencial** (Resilience4j `retry.instances.transactionProvider`):
     reintenta timeouts/fallos de comunicación, nunca rechazos de negocio.
  4. **Circuit Breaker** (Resilience4j `circuitbreaker.instances.transactionProvider`):
     deja de intentar contra un proveedor caído, con transición automática a
     half-open.
  Un rechazo del proveedor (`ProviderRejectedException`, ej. fondos insuficientes) está
  explícitamente excluido de retry y circuit breaker — es un resultado de negocio válido,
  no un fallo de infraestructura.
- **Por qué no hay tabla de dead-letter separada**: el propio `status=FAILED` +
  columna `failure_reason` en `transactions` cumple ese propósito. Es una API síncrona
  sin cola ni reprocesamiento automático — una tabla de dead-letter añadiría
  infraestructura sin un consumidor real que la procese en este alcance.
- **Por qué no hay particionamiento de PostgreSQL ni split de pools lectura/escritura**:
  quedan documentados como próximos pasos de escalabilidad, descartados del alcance por
  pragmatismo dado el tiempo del challenge — no hay evidencia de que el volumen actual
  los justifique.
- **Versionado de API vía header `X-API-Version`** (soporte nativo de Spring Framework 7,
  no un prefijo de URL): con versión por defecto (`"1"`) configurada, un cliente que no
  envía el header sigue funcionando — el versionado es una red de seguridad hacia el
  futuro, no una barrera de entrada.

## Cómo levantar el proyecto

**1. Levantar la infraestructura** (PostgreSQL + proveedor mock vía WireMock):

```bash
docker-compose up -d
```

Esto expone PostgreSQL en `localhost:5433` y el proveedor mock en `localhost:9090`
(los stubs de WireMock viven en `wiremock/mappings/` y se cargan automáticamente).

**2. Levantar la aplicación**:

```bash
./gradlew bootRun
```

La API queda disponible en `http://localhost:8080`.

**Perfil `local`** (para demostrar resiliencia manualmente — ver sección de Postman):
reduce los umbrales del circuit breaker para poder abrirlo en minutos en vez de
necesitar 10+ llamadas fallidas reales, y expone endpoints de actuator para inspeccionar
su estado:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

o, equivalentemente:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## Cómo correr los tests

```bash
./gradlew test
```

Cuatro capas de test, cada una con el alcance mínimo necesario para ser rápida y
confiable:

| Capa | Herramienta | Qué cubre |
|---|---|---|
| `service/` | JUnit 5 + Mockito puro | Reglas de negocio, idempotencia, mapeo de resultados del proveedor — sin Spring, milisegundos por test |
| `repository/` | Testcontainers (Postgres real) | SQL real, migraciones de Flyway, el `upsert` de idempotencia, filtros y paginación |
| `provider/` | WireMock | Timeouts, retry con backoff, circuit breaker — contra un servidor HTTP real, no mocks de Java |
| `controller/` | MockMvc + Testcontainers + WireMock | Stack completo de punta a punta: HTTP → controller → service → repository real → proveedor real (mockeado a nivel de red) |

> **Nota**: los tests de `provider/` y `controller/` levantan su propia instancia de
> WireMock en el puerto `9090` (independiente de Testcontainers). Si el
> `mock-provider` de `docker-compose` sigue corriendo en ese mismo puerto al ejecutar
> `./gradlew test`, habrá conflicto de puerto. Bajarlo con `docker-compose stop
> mock-provider` (o `docker-compose down`) antes de correr los tests automatizados —
> no hace falta para `./gradlew bootRun`, solo para los tests.

## Probar manualmente con Postman

Importar `postman/financial-transactions.postman_collection.json`. La colección asume
la app corriendo en `http://localhost:8080` y WireMock en `http://localhost:9090`
(variables de colección `baseUrl`/`wiremockUrl`, editables).

Está organizada en carpetas numeradas, pensadas para ejecutarse en orden:

1. **Happy Path** — CREDIT válido, aprobado por el proveedor.
2. **Business Rule Validations** — montos inválidos, límite de DEBIT, moneda no
   soportada, header faltante.
3. **Provider Rejection** — el proveedor rechaza la transacción (resultado de negocio,
   no error HTTP).
4. **Idempotency** — reutilización exitosa, y conflicto con datos distintos.
5. **Timeout & Retry Demo** — el proveedor se cuelga; se ve el reintento interno de
   Resilience4j en el journal de WireMock; y el reintento de una transacción FAILED.
6. **Circuit Breaker Demo** (requiere el perfil `local` activo) — llamadas fallidas
   consecutivas abren el circuito; se confirma que las siguientes llamadas fallan
   instantáneamente sin tocar la red; inspección de `/actuator/health` y
   `/actuator/circuitbreakerevents`.
7. **Query & Pagination** — filtros por status/type y paginación.

**"Interruptores mágicos"** para disparar cada escenario del proveedor mock (ver
`wiremock/mappings/`):

| Valor en el request | Efecto |
|---|---|
| `amount: 9999999` | El proveedor rechaza con `INSUFFICIENT_FUNDS` (402) |
| `accountId: "acc-timeout-test"` | El proveedor se cuelga 8s (dispara timeout + retry) |
| `accountId: "acc-cb-demo"` | Scenario de 3 pasos: dos fallos seguidos, luego recupera — para demostrar el circuit breaker |
| Cualquier otro valor | El proveedor aprueba automáticamente |

Cada request de la colección incluye una descripción explicando qué demuestra y qué
respuesta esperar, para poder usarla sin tener este README abierto en paralelo.

## Endpoints

| Método | Path | Descripción |
|---|---|---|
| `POST` | `/transactions` | Ejecuta una transacción (requiere header `Idempotency-Key`) |
| `GET` | `/transactions` | Consulta transacciones (query params: `accountId`, `status`, `type`, `page`, `limit`) |

Ambos requieren el header `X-API-Version` (opcional; por defecto `"1"` si se omite).

## Uso de Inteligencia Artificial

Se usó Claude (Anthropic) durante el desarrollo de este challenge para: generar
boilerplate repetitivo (DTOs, migraciones, configuración), resolver dudas de
arquitectura y de APIs específicas de Spring Boot 4 / Spring Framework 7 (varias de
ellas verificadas contra el código fuente real de los módulos, dado lo reciente del
framework), debugging de fallos de integración (timing de Testcontainers/WireMock,
interacción entre Resilience4j y el pool de conexiones HTTP), y para generar la suite
de tests siguiendo un enfoque BDD (given/when/then) a partir de casos diseñados
previamente por el autor, no decididos por la IA: para cada capa se definió de
antemano qué comportamientos cubrir, qué límites/casos borde probar (ej. límite exacto
de DEBIT, monto mínimo, transiciones de estado de idempotencia) y qué tipo de test le
correspondía (unitario con Mockito, integración con Testcontainers, integración con
WireMock, o integración de extremo a extremo) — la IA implementó esos casos ya
definidos, no decidió unilateralmente cuáles escribir.
