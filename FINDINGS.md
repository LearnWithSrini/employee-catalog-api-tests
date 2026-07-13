# Findings — Potential Gaps in the Employee Catalog API

Observations captured while exploring the service and building the automated
tests. These are the kinds of defects/inconsistencies a tester would raise with
the development team. Severity is a rough judgement of business impact.

| # | Severity | Endpoint | Observation |
|---|----------|----------|-------------|
| 1 | **High** | `POST /employees`, `GET /employees` | **`dateOfBirth` is truncated to the year.** Sending `"1991-04-12"` on create returns `"dateOfBirth":"1991"`, and the value listed by `GET /employees` is likewise just the year. Day and month are silently lost, so date of birth cannot be relied upon. (`GET /employees/{id}` separately returns a full ISO date-time — see #2.) |
| 2 | Medium | Employee reads | **Inconsistent `dateOfBirth` representation across endpoints.** The docs show `GET /employees/{id}` returning a full ISO date-time (`1980-03-15T00:00:00.000Z`) while `GET /employees` returns only a year string. A field should have one consistent type/format across the API. |
| 3 | Medium | `POST /hr/login` | **Login error contract does not match the docs.** On invalid credentials the service returns `{"error":"Invalid credentials"}`, but the OpenAPI spec documents `{"message":"Invalid credentials"}`. Consumers coding to the spec would read the wrong field. |
| 4 | Medium | `POST /hr/login` (success) | **Undocumented response field.** A successful login also returns `accountExpirationDate`, which is not described in the API documentation. |
| 5 | **Critical** | `POST /employees` validation | **Invalid input crashes the service (availability defect).** Posting a body missing a required field (e.g. `lastName`) or an empty `{}` body returns `502`/`503` instead of a clean `400`, because the validation path throws an **unhandled error that crashes the single free-tier process**. Since one process serves both the API and the `/api-docs` Swagger UI, *both go down together*, then Render auto-restarts. Re-sending the same bad payload re-crashes it, so a client that retries 5xx can hold the whole service down. Observed directly: the service and UI drop the moment the negative-create tests run and recover when they stop. A robust API must return a deterministic `400` and never terminate on bad input. **Mitigation in this suite:** the malformed-input tests now send exactly once (`createEmployeeNoRetry`) so they can't turn one crash into a sustained outage. |
| 6 | Low | Docs vs. security scheme | The spec declares a `bearerAuth` security scheme but does not attach a `security` requirement to the individual employee operations, even though they *do* enforce auth at runtime. The documentation understates the auth requirement. |
| 8 | **High** | `POST /employees` | **Documented-minimal payload is not accepted.** A body carrying only the documented required fields (`firstName`, `lastName`, `dateOfBirth`, `contactInfo.email`) but no `phone`/`address` fails on the live host (5xx / crash rather than a `201`). The service appears to require the full nested `contactInfo` even though the docs state only `email` is required — either a validation-contract mismatch or the same crash-on-absent-field fragility as #5. The `minimal payload is accepted` test is parked (`@Disabled`) until this is fixed. |
| 7 | Low | Hosting | The free-tier host cold-starts (first request up to ~60s) and occasionally returns transient `502/503`. This is an environment characteristic rather than an API defect, but it makes naive tests flaky — the suite mitigates it with warm-up + retry on login. |

## How the tests relate to these findings

- The suite asserts on **status codes and behaviour** (e.g. duplicate email → 400,
  auth required → 401, delete then 404), which is stable regardless of the
  `dateOfBirth` quirk.
- Because finding #1/#2 make `dateOfBirth` unreliable, the tests deliberately do
  **not** assert an exact stored `dateOfBirth` value; the `Employee` model keeps
  it as a raw `String` and the observation is documented here instead. This keeps
  the suite green while still surfacing the defect for the developers.
- Findings #3–#6 are documentation/contract issues; they are recorded here as
  the "gaps in the implementation" the exercise asks the tester to identify.
