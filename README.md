# Employee Catalog Management API — Automated Test Suite

Automated **API test suite** for the **Employee Catalog Management System**, written
for the DWP back-end test exercise.

The goal of the exercise is *not* to build the API — it is to act as a **tester**:
evaluate the live service against its stated business requirements, prove the
happy-path user journeys with automated tests, exercise every endpoint, and
surface any implementation gaps.

- **Language / build:** Java 21 · Maven
- **Test stack:** JUnit 5 (Jupiter) · REST-assured · Hamcrest · Jackson · Datafaker
- **Service under test:** `https://apisforemployeecatalogmanagementsystem.onrender.com`

---

## Table of contents

1. [The system under test](#1-the-system-under-test)
2. [How authentication works](#2-how-authentication-works)
3. [Quick start](#3-quick-start)
4. [Configuration](#4-configuration)
5. [Project structure — every file explained](#5-project-structure--every-file-explained)
6. [Test coverage](#6-test-coverage)
7. [Resilience: coping with a flaky free-tier host](#7-resilience-coping-with-a-flaky-free-tier-host)
8. [Test data strategy](#8-test-data-strategy)
9. [Findings / implementation gaps](#9-findings--implementation-gaps)
10. [Design decisions & FAQ](#10-design-decisions--faq)

---

## 1. The system under test

The API manages employee records for HR administrators. Only authenticated HR
admins may read or modify the catalog. It exposes six operations:

| Method & path | Purpose | Auth required |
|---------------|---------|:-------------:|
| `POST /hr/login` | Authenticate an HR admin, returns a JWT | ❌ |
| `POST /employees` | Add a new employee | ✅ |
| `GET /employees` | List all employees | ✅ |
| `GET /employees/{id}` | Get one employee by id | ✅ |
| `PUT /employees/{id}` | Amend an existing employee | ✅ |
| `DELETE /employees/{id}` | Remove an employee | ✅ |

**Employee shape** (request body for create):

```json
{
  "firstName": "Peter",
  "lastName": "Pan",
  "dateOfBirth": "2010-01-13",
  "contactInfo": {
    "email": "peter.pan@example.com",
    "phone": "+4434567890",
    "address": { "street": "123 Main St", "town": "Manchester", "postCode": "M12 3T2" }
  }
}
```

Required fields: `firstName`, `lastName`, `dateOfBirth`, and `contactInfo.email`.
The server generates the id (e.g. `Emp-ce2f233c-...`) and enforces a **unique
email** constraint.

---

## 2. How authentication works

Authentication is a two-step, token-based (JWT bearer) flow — this is the single
most important thing the suite has to get right, because every employee endpoint
depends on it.

```
┌────────────┐   1. POST /hr/login                 ┌─────────────────────────┐
│  Test /    │   { username, password }            │  Employee Catalog API   │
│  client    │ ──────────────────────────────────▶ │                         │
│            │                                      │  validates credentials  │
│            │   2. 200 OK { "token": "<JWT>" }     │  signs a short-lived JWT │
│            │ ◀────────────────────────────────── │                         │
│            │                                      │                         │
│            │   3. GET/POST/PUT/DELETE /employees  │                         │
│            │      Authorization: Bearer <JWT>     │  verifies the JWT on     │
│            │ ──────────────────────────────────▶ │  every employee call     │
└────────────┘                                      └─────────────────────────┘
```

1. **Log in.** `POST /hr/login` with `{"username","password"}`. Valid credentials
   return `200` and a body containing a **JWT** (`token`). Invalid credentials
   return `401`.
2. **Carry the token.** Every employee request must send the header
   `Authorization: Bearer <token>`. A missing/blank/garbage token is rejected
   with `401`.
3. **Credentials.** The exercise supplies ten interchangeable HR admins,
   `admin1`…`admin10`, all with the password `securePassword`. The suite uses
   `admin1` by default (configurable — see [§4](#4-configuration)).

**Where this lives in the code:**
- The raw login call is `EmployeeCatalogClient.login(username, password)` — it
  returns the untouched response so both positive and negative login tests can
  assert on it (`LoginTests`).
- `EmployeeCatalogClient.warmUpAndLogin()` performs the login once, extracts the
  JWT, and hands it back. It is called a single time in `BaseTest` and the token
  is **cached and reused** across the whole suite, so we don't hammer the login
  endpoint before every test.
- `EmployeeCatalogClient.authedRequest(token)` is the private helper that attaches
  the `Authorization: Bearer <token>` header to every authenticated request.
- The `*NoAuth` client methods (e.g. `getAllEmployeesNoAuth`) deliberately omit
  the header so `AuthorizationTests` can prove each endpoint rejects unauthenticated
  access.

> The token is a short-lived JWT (the login response also includes an
> `accountExpirationDate`). Because a single run completes well within its
> lifetime, the suite fetches one token per run; nothing needs to refresh it.

---

## 3. Quick start

### Prerequisites
- **JDK 21+** (any distribution — Temurin, OpenJDK, etc.)
- **Maven 3.9+**

Check with `java -version` and `mvn -version`.

### Run the whole suite
```bash
mvn clean test
```

The service is hosted on a **free tier that cold-starts** — the first request can
take up to ~60 seconds, and the host occasionally returns transient `502/503`
while waking. The suite handles this automatically (see [§7](#7-resilience-coping-with-a-flaky-free-tier-host)),
so no manual warm-up is needed; just allow the first run some time.

### Run a subset
```bash
mvn test -Dtest=EmployeeLifecycleJourneyTest      # only the end-to-end journey
mvn test -Dtest=LoginTests,AuthorizationTests     # just auth-related tests
mvn test -Dtest='Create*'                          # pattern match
```

### Reports
After a run, JUnit results are written to `target/surefire-reports/` (one XML +
text file per test class).

### Run history (last 10 runs)
A `TestExecutionListener` (`util/TestRunHistoryListener`) appends a **timestamped
one-line summary** of every run to `test-history/test-results.log`, keeping only
the **most recent 10 runs**. It runs automatically on every `mvn test` — no
wiring needed — and lives outside `target/`, so `mvn clean` doesn't wipe it. Example:

```
2026-07-14 15:52:49 | duration=   3.5s | total= 2  passed= 2  failed= 0  skipped= 0 | RESULT=PASS
```

The `skipped` count includes `@Disabled` tests and any aborted on a host outage,
so it's a handy record of how the suite behaved against the flaky free-tier host
over its last few runs. The file is git-ignored (it's a local artifact).

---

## 4. Configuration

All configuration lives in [`src/test/resources/config.properties`](src/test/resources/config.properties).
**Any value can be overridden on the command line** with `-D<key>=<value>`, which
is handy for pointing the suite at a local instance or a different admin.

| Key | Default | Meaning |
|-----|---------|---------|
| `base.uri` | the Render URL | Base URL of the API under test |
| `admin.username` | `admin1` | HR admin username used to log in |
| `admin.password` | `securePassword` | HR admin password |
| `retry.max.seconds` | `40` | Per-call budget for retrying transient gateway 5xx |
| `retry.backoff.millis` | `2500` | Wait between transient-retry attempts |
| `warmup.max.seconds` | `120` | Budget for the initial cold-start login |

Examples:
```bash
mvn test -Dbase.uri=http://localhost:3000           # test a local build
mvn test -Dadmin.username=admin2                     # log in as a different admin
mvn test -Dretry.max.seconds=90                      # be more patient with the host
```

---

## 5. Project structure — every file explained

```
employee-catalog-api-tests/
├── pom.xml
├── README.md
├── FINDINGS.md
├── .gitignore
└── src/test/
    ├── java/com/dwp/employeecatalog/
    │   ├── api/      → REST-assured plumbing (how we talk to the API)
    │   ├── model/    → POJOs (the request/response data shapes)
    │   ├── util/     → configuration + fake-data helpers
    │   └── tests/    → the actual tests
    └── resources/    → config + logging properties
```

Everything is under `src/test` on purpose: this is a **test project**, not an
application, so there is no `src/main`. The classes in `api`, `model`, and `util`
are test support code.

### Build & config

| File | What it does |
|------|--------------|
| [`pom.xml`](pom.xml) | Maven build. Declares Java 21, the dependencies (REST-assured, JUnit 5, Hamcrest, Jackson, Datafaker, SLF4J) and the Surefire plugin that runs the JUnit 5 suite during `mvn test`. |
| [`src/test/resources/config.properties`](src/test/resources/config.properties) | The single source of runtime configuration — base URL, credentials, retry tuning. See [§4](#4-configuration). |
| [`src/test/resources/junit-platform.properties`](src/test/resources/junit-platform.properties) | JUnit 5 platform settings: readable `@DisplayName` generation and deterministic (sequential) execution, since all classes share one remote server. |
| [`src/test/resources/simplelogger.properties`](src/test/resources/simplelogger.properties) | SLF4J-simple logging format so the console output during a run stays concise and timestamped. |

### `model/` — the data shapes (POJOs)

Plain Java objects that Jackson serialises into request JSON and deserialises from
responses. They mirror the API's schema exactly.

| File | What it represents |
|------|--------------------|
| [`model/Employee.java`](src/test/java/com/dwp/employeecatalog/model/Employee.java) | An employee (id, first/last name, date of birth, contact info). Includes a small fluent **`builder()`** so tests can construct employees readably. `dateOfBirth` is kept as a raw `String` on purpose — the API is inconsistent about its format (see [Findings](#9-findings--implementation-gaps)), so we don't bind it to a strict date type. |
| [`model/ContactInfo.java`](src/test/java/com/dwp/employeecatalog/model/ContactInfo.java) | Nested contact block: `email` (required), `phone`, `address`. |
| [`model/Address.java`](src/test/java/com/dwp/employeecatalog/model/Address.java) | Nested postal address: `street`, `town`, `postCode`. |
| [`model/LoginRequest.java`](src/test/java/com/dwp/employeecatalog/model/LoginRequest.java) | The `{username, password}` body for `POST /hr/login`. |

All models use `@JsonInclude(NON_NULL)` (so optional fields we don't set are
omitted from the request) and `@JsonIgnoreProperties(ignoreUnknown = true)` (so
extra/undocumented response fields never break deserialisation).

### `api/` — how we talk to the API

| File | What it does |
|------|--------------|
| [`api/Endpoints.java`](src/test/java/com/dwp/employeecatalog/api/Endpoints.java) | String constants for the API paths (`/hr/login`, `/employees`, `/employees/{id}`) — one place to change if a path ever moves. |
| [`api/EmployeeCatalogClient.java`](src/test/java/com/dwp/employeecatalog/api/EmployeeCatalogClient.java) | **The heart of the suite.** A thin, reusable REST-assured client. It owns the base request spec (base URI, JSON content type, logging filters), the **authentication** helpers (`login`, `warmUpAndLogin`, `authedRequest`), one method per endpoint (both authenticated and `*NoAuth` variants), convenience helpers like `createEmployeeAndReturnId`, and the **transient-error retry / skip logic** (see [§7](#7-resilience-coping-with-a-flaky-free-tier-host)). Every test goes through this client rather than calling REST-assured directly, so HTTP concerns stay in one place. |

### `util/` — configuration & fake data

| File | What it does |
|------|--------------|
| [`util/ConfigManager.java`](src/test/java/com/dwp/employeecatalog/util/ConfigManager.java) | Loads `config.properties` from the classpath and exposes typed accessors (`baseUri()`, `adminUsername()`, …). Crucially, a matching **JVM system property overrides** the file value, enabling the `-Dkey=value` overrides in [§4](#4-configuration). |
| [`util/TestDataFactory.java`](src/test/java/com/dwp/employeecatalog/util/TestDataFactory.java) | Produces **fake, synthetic** employees via Datafaker so no real personal data is used. Emails are salted with a random token to guarantee uniqueness (the API rejects duplicate emails). Also provides `minimalValidEmployee()` and `nonExistentEmployeeId()` for edge-case tests. See [§8](#8-test-data-strategy). |

### `tests/` — the tests

| File | Endpoint / concern | Highlights |
|------|--------------------|-----------|
| [`tests/BaseTest.java`](src/test/java/com/dwp/employeecatalog/tests/BaseTest.java) | Shared setup | Configures generous HTTP timeouts for the cold start and authenticates **once** (`@BeforeAll`), caching the bearer token for all subclasses. |
| [`tests/LoginTests.java`](src/test/java/com/dwp/employeecatalog/tests/LoginTests.java) | `POST /hr/login` | Valid login returns a JWT; wrong password, unknown user, empty and wrong-case credentials are all rejected with `401` and leak no token. |
| [`tests/AuthorizationTests.java`](src/test/java/com/dwp/employeecatalog/tests/AuthorizationTests.java) | Cross-cutting auth | Proves **every** employee endpoint rejects missing tokens with `401`, and that a garbage token is rejected. |
| [`tests/CreateEmployeeTests.java`](src/test/java/com/dwp/employeecatalog/tests/CreateEmployeeTests.java) | `POST /employees` | Happy path (`201` + generated id), minimal payload, duplicate-email (`400`), duplicate first name (allowed), empty/missing required fields. Cleans up created records in `@AfterEach`. |
| [`tests/GetAllEmployeesTests.java`](src/test/java/com/dwp/employeecatalog/tests/GetAllEmployeesTests.java) | `GET /employees` | Returns `200` + a JSON array, every item has an id, and a freshly created employee appears in the list. |
| [`tests/GetEmployeeByIdTests.java`](src/test/java/com/dwp/employeecatalog/tests/GetEmployeeByIdTests.java) | `GET /employees/{id}` | An existing id returns the matching record; an unknown id returns `404`. |
| [`tests/UpdateEmployeeTests.java`](src/test/java/com/dwp/employeecatalog/tests/UpdateEmployeeTests.java) | `PUT /employees/{id}` | An update returns `200` and the change is **read back** to confirm it persisted; updating an unknown id returns `404`. |
| [`tests/DeleteEmployeeTests.java`](src/test/java/com/dwp/employeecatalog/tests/DeleteEmployeeTests.java) | `DELETE /employees/{id}` | Deleting removes the record (a subsequent GET returns `404`); deleting an unknown id returns `404`. |
| [`tests/EmployeeLifecycleJourneyTest.java`](src/test/java/com/dwp/employeecatalog/tests/EmployeeLifecycleJourneyTest.java) | **End-to-end journey** | The headline scenario the exercise asks for: an HR admin **logs in → adds → verifies (by id and in the list) → amends → removes → confirms gone**, as seven ordered, state-sharing steps. |

---

## 6. Test coverage

**32 tests** in total, all with explicit assertions:

- **Authentication (`LoginTests`)** — 5 tests: token issuance and four rejection paths.
- **Authorization (`AuthorizationTests`)** — 5 tests: every endpoint requires a valid bearer token.
- **Create (`CreateEmployeeTests`)** — 6 tests: happy path + validation/negative cases.
- **Read all (`GetAllEmployeesTests`)** — 3 tests.
- **Read by id (`GetEmployeeByIdTests`)** — 2 tests.
- **Update (`UpdateEmployeeTests`)** — 2 tests.
- **Delete (`DeleteEmployeeTests`)** — 2 tests.
- **End-to-end journey (`EmployeeLifecycleJourneyTest`)** — 7 ordered steps.

Per the exercise guidelines, **no non-functional tests** (performance/security)
are included.

---

## 7. Resilience: coping with a flaky free-tier host

The API is hosted on a **free Render tier**, which has two awkward behaviours:

1. **Cold start** — after inactivity the first request can take up to ~60 seconds.
2. **Transient gateway errors** — while waking or under load it briefly returns
   `502` / `503` from the edge, *before the request ever reaches the application*.

A naive suite would be hopelessly flaky against this. The client distinguishes
**infrastructure blips** from **real API responses** and handles them so that:

> ✅ A real application response (`2xx`/`4xx`, even an app-level `500`) is always
> returned to the test for assertion.
> 🔁 A transient gateway `502/503/504` is **retried** within a time budget
> (`retry.max.seconds`), because it means the request never reached the app —
> so retrying is safe even for `POST`.
> ⏭️ If the gateway keeps failing for the *entire* budget, the call throws
> `TestAbortedException`, which JUnit reports as **skipped**, not failed — an
> environment outage must not fail the build, but a genuine defect still will.

The initial login (`warmUpAndLogin`) has its own, larger budget
(`warmup.max.seconds`) to absorb the cold start.

**What this means when you run it:** on a warm host, all 32 tests pass. During a
bad patch on the free host, some tests may show as *skipped* (with a clear
"service unreachable" message) rather than failing — re-running when the host is
warm turns them green. This is deliberate: **skips = environment, failures = the API**.

---

## 8. Test data strategy

- **All data is fake / synthetic**, generated with [Datafaker](https://www.datafaker.net/)
  (`TestDataFactory`) — no real personal data is ever sent, per the exercise rules.
- **Emails are unique per object** (salted with a random UUID fragment and an
  `@example.test` domain) so re-runs never collide with the API's unique-email
  constraint.
- **Tests clean up after themselves.** Create/journey tests delete the employees
  they add (`@AfterEach` or a `finally` block), keeping the shared remote catalog
  tidy and keeping runs independent and repeatable.

---

## 9. Findings / implementation gaps

The exercise asks the tester to identify gaps in the implementation. These are
documented in **[FINDINGS.md](FINDINGS.md)**. Headlines:

- **`dateOfBirth` is truncated to the year** on create/list (day and month are lost).
- **Inconsistent `dateOfBirth` format** across endpoints (year vs full ISO date-time).
- **Login error contract mismatch** — the service returns `{"error": ...}` where
  the docs specify `{"message": ...}`.
- **Undocumented `accountExpirationDate`** field in the login success response.
- **Unstable validation path** — invalid create payloads sometimes return `5xx`
  instead of a clean `400`.

The tests are written to stay stable despite these (e.g. they don't assert an
exact stored `dateOfBirth`), while the observations are captured for the developers.

---

## 10. Design decisions & FAQ

**Why REST-assured + JUnit 5?** REST-assured gives a fluent, readable HTTP DSL
purpose-built for API testing; JUnit 5 is the current standard runner with good
lifecycle hooks, ordering, and display names. Hamcrest supplies expressive
assertion matchers.

**Why is there no `src/main`?** This is a test project that exercises an external
service. All code is test-support code, so it lives under `src/test`.

**Why cache one token for the whole run?** Logging in before every test would be
slow and would pound the login endpoint unnecessarily. One token per run is
sufficient and realistic; `LoginTests` still exercises the login endpoint
directly for its own assertions.

**Why does the client, not each test, own the retry logic?** So every call gets
consistent resilience for free and the tests stay focused on *behaviour and
assertions* rather than HTTP mechanics.

**Can I point this at a local instance?** Yes — `mvn test -Dbase.uri=http://localhost:PORT`.

**How do I submit this?** Run `mvn clean` first (so `target/` is excluded), then
zip the project. Do not include `exe/sh/bat/bin/json` files, per the exercise
instructions.
