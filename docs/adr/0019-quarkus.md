# ADR-0019: Server: Kotlin + Quarkus

- **Stav:** Přijato
- **Oblast:** Technologie
- **Datum:** 2026-10-02

## Kontext

Autor zná Micronaut a chce se naučit Quarkus.

## Rozhodnutí

Kotlin, Java 21, Quarkus (REST, REST client, scheduler, Flyway, Micrometer, SmallRye OpenAPI).

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Micronaut | Známý, ale cílem je naučit se Quarkus. |
| Spring Boot | Těžší, autor ho nezná. |

## Důsledky

- Dev Services spustí Postgres v dev režimu a testech automaticky.
