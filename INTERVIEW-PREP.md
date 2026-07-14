# Interview Prep — Q&A

A quick-revision questionnaire for this project (the **Employee Catalog Management
API** automated test suite). It starts with the exact questions covered while
building it, then broadens into the questions an interviewer is likely to ask
about the code, the tools, and the testing approach.

> Tip: read each **Q** and try to answer out loud before reading the **A**.

---

## Table of contents

1. [Maven & `pom.xml` fundamentals](#1-maven--pomxml-fundamentals)
2. [The project & test framework](#2-the-project--test-framework)
3. [Authentication](#3-authentication)
4. [REST-assured & JUnit 5](#4-rest-assured--junit-5)
5. [Test design & resilience](#5-test-design--resilience)
6. [Testing mindset & findings](#6-testing-mindset--findings)
7. [Java / POJOs / Jackson](#7-java--pojos--jackson)
8. [Rapid-fire one-liners](#8-rapid-fire-one-liners)

---

## 1. Maven & `pom.xml` fundamentals

**Q1. What is the latest version of Maven?**
**A.** As of mid-2026 the latest **stable** release is **Apache Maven 3.9.16** (recommended for production, needs JDK 8+). Maven **4.0.0** exists only as a preview/release-candidate (`4.0.0-rc-5`, needs JDK 17+) and is not production-safe yet. This project builds with 3.9.16.

**Q2. The `pom.xml` shows `4.0.0` — is that the Maven version?**
**A.** No. That is `<modelVersion>4.0.0</modelVersion>`, the **POM model (file-format) version** — the schema the `pom.xml` conforms to. It has been `4.0.0` for the entire Maven 3.x line and remains `4.0.0` in Maven 4. It is effectively a constant and is unrelated to the Maven *tool* version.

**Q3. Where is the Maven (tool) version mentioned?**
**A.** Nowhere in the project, by design. A `pom.xml` doesn't pin the Maven binary. The real version is whatever is installed — check with `mvn -version` (here: 3.9.16). You *can* optionally pin/enforce it via the **Maven Wrapper** (`.mvn/wrapper/maven-wrapper.properties`) or the **`maven-enforcer-plugin`** (`requireMavenVersion`), neither of which this project uses.

**Q4. What is `pom.xml` and what is its purpose?**
**A.** POM = **Project Object Model**. It's the single file that describes *what the project is* and *how to build it*. Its four jobs:
1. **Identify** the project via coordinates — `groupId` + `artifactId` + `version` (`com.dwp:employee-catalog-api-tests:1.0.0`).
2. **Declare dependencies** — Maven auto-downloads them from Maven Central (REST-assured, JUnit, Hamcrest, Jackson, Datafaker).
3. **Configure the build** — Java version, plugins (e.g. Surefire runs the tests).
4. **Define structure & lifecycle** — by convention: code in `src/main/java`, tests in `src/test/java`, output in `target/`.
The payoff: anyone can `mvn clean test` and get an **identical, reproducible build** on any machine.

**Q5. What are Maven "coordinates"?**
**A.** The unique address of an artifact: `groupId:artifactId:version` (GAV). It's how Maven names your output and how it locates dependencies in a repository.

**Q6. What is the Maven build lifecycle / what does `mvn clean test` do?**
**A.** Maven runs ordered **phases**. `clean` deletes `target/`; `test` runs `validate → compile → test-compile → test`, so it compiles main + test code and executes the tests. `mvn clean` before zipping (per the exercise) ensures no build output is shipped.

**Q7. What is Maven Central / the local repository (`~/.m2`)?**
**A.** Maven Central is the default public repository of libraries. Maven downloads dependencies once into your local cache at `~/.m2/repository` and reuses them across projects, so builds don't re-download every time.

**Q8. What is dependency `scope`, and why is everything here `test`?**
**A.** Scope controls *when* a dependency is on the classpath. `test` scope means it's available only for compiling/running tests and is not shipped as a runtime dependency — correct here, because this is a pure test project (no `src/main`).

---

## 2. The project & test framework

**Q9. What does this project do?**
**A.** It's an **automated API test suite** that evaluates the live Employee Catalog Management API against its business requirements — proving the HR-admin happy path (login → add → amend → remove) and exercising every endpoint, with assertions, plus documenting implementation gaps. It does **not** build the API.

**Q10. What's the tech stack and why these choices?**
**A.** Java 21 + Maven; **JUnit 5** (test runner/lifecycle), **REST-assured** (fluent HTTP DSL built for API testing), **Hamcrest** (expressive assertion matchers), **Jackson** (JSON ↔ POJO), **Datafaker** (synthetic test data). Each is the standard, well-supported choice for its job.

**Q11. Why is there no `src/main/java`?**
**A.** This project tests an external service; all the code is test-support code, so it lives under `src/test`. There's no application to ship.

**Q12. Walk me through the project structure.**
**A.**
- `api/` — REST-assured plumbing: `EmployeeCatalogClient` (one method per endpoint + auth + retry) and `Endpoints` (path constants).
- `model/` — POJOs mirroring the API JSON: `Employee`, `ContactInfo`, `Address`, `LoginRequest`.
- `util/` — `ConfigManager` (config + `-D` overrides) and `TestDataFactory` (fake data).
- `tests/` — `BaseTest` + one class per endpoint + the end-to-end journey.
- `src/test/resources/` — `config.properties`, `junit-platform.properties`, logging.

**Q13. How do you run the tests? A single test?**
**A.** `mvn clean test` for all; `mvn test -Dtest=EmployeeLifecycleJourneyTest` for one class; patterns like `-Dtest='Create*'` also work.

**Q14. How is configuration handled and overridden?**
**A.** `config.properties` holds base URL, credentials and retry tuning. `ConfigManager` lets any JVM system property override the file value, so `mvn test -Dbase.uri=http://localhost:3000` re-points the suite (e.g. at a local build) with no code change.

---

## 3. Authentication

**Q15. How does authentication work in this API?**
**A.** Token-based (JWT bearer), two steps: (1) `POST /hr/login` with `{username,password}` returns `200` + a JWT; (2) every employee request must send `Authorization: Bearer <token>`. Missing/invalid tokens get `401`.

**Q16. How does the suite handle the token?**
**A.** `EmployeeCatalogClient.warmUpAndLogin()` logs in **once**; `BaseTest` caches the token in an `@BeforeAll` and all test classes reuse it — so we don't hit the login endpoint before every test. `authedRequest(token)` attaches the `Bearer` header.

**Q17. How do you test that endpoints actually require auth?**
**A.** The client exposes `*NoAuth` variants (e.g. `getAllEmployeesNoAuth`) that omit the header; `AuthorizationTests` calls each endpoint without a token and asserts `401`, and also sends a garbage token and asserts it's rejected.

**Q18. What is a JWT, briefly?**
**A.** A JSON Web Token — a signed, self-contained token with three dot-separated parts (header.payload.signature). The server verifies the signature on each request; no server-side session needed. It's short-lived (this API also returns an `accountExpirationDate`).

**Q19. Why log in once instead of per test?**
**A.** Performance and realism — repeatedly hammering `/hr/login` is wasteful and unrealistic. One token per run is sufficient and well within its lifetime; `LoginTests` still exercises login directly for its own assertions.

---

## 4. REST-assured & JUnit 5

**Q20. Why REST-assured?**
**A.** It gives a readable, fluent DSL purpose-built for HTTP/JSON API testing — request specs, JSON path extraction, and integration with matchers — so tests focus on behaviour, not low-level HTTP client code.

**Q21. What is a `RequestSpecification` and why build a base one?**
**A.** A reusable template of common request settings (base URI, JSON content type, logging filters). Building it once in the client keeps every call consistent and DRY.

**Q22. How do you assert on responses?**
**A.** Extract values with `response.jsonPath().getString("employeeId")` etc., then assert with Hamcrest (`assertThat(status, is(201))`). Assertions carry messages so failures are self-explaining.

**Q23. Key JUnit 5 annotations used here?**
**A.** `@Test`, `@DisplayName` (readable names), `@BeforeAll`/`@AfterEach` (setup/cleanup), `@Order` + `@TestMethodOrder` (the ordered journey), `@Disabled` (parked tests). Assertions come from Hamcrest via `assertThat`.

**Q24. Why order the end-to-end journey with `@Order`?**
**A.** Because it's one coherent story with shared state — add must happen before amend before remove. Most other test classes are independent and unordered on purpose; ordering is the exception, used only where the scenario is inherently sequential.

**Q25. What's the difference between a failed test and a skipped/aborted one here?**
**A.** A **failure** = the API returned something wrong (a real defect). A **skip** (via `TestAbortedException`) = the request never reached the app (infrastructure outage). This distinction keeps the build honest: env problems don't masquerade as API bugs.

---

## 5. Test design & resilience

**Q26. The API is on a flaky free host — how did you make the suite stable?**
**A.** The client separates **infra blips** from **real responses**: transient gateway `502/503/504` (which mean the request never reached the app) are **retried** within a time budget; a genuine `2xx/4xx` is returned for assertion. If the gateway keeps failing for the whole budget, the call throws `TestAbortedException` → the test is **skipped**, not failed.

**Q27. Isn't retrying a `POST` dangerous (double-create)?**
**A.** Only if the request reached the app. A `502/503/504` is a *gateway* status meaning it didn't — so retrying is safe. Real app responses are never retried.

**Q28. Then why do some create tests use `createEmployeeNoRetry`?**
**A.** Because of a discovered defect: invalid payloads crash the single free-tier instance (FINDINGS #5). Retrying a bad payload would re-crash it on restart and cause a sustained outage. Sending it **once** limits the blast radius so the host recovers.

**Q29. Why are some negative tests `@Disabled`?**
**A.** Same defect — sending malformed input to the *shared live host* takes the API and its docs UI down. They're kept (not deleted) with a clear reason so they can run against a fixed or local build. That's a deliberate, documented trade-off, not dead code.

**Q30. How do you keep tests independent and repeatable?**
**A.** Every created employee is deleted in `@AfterEach`/`finally`, and emails are made unique per object (UUID-salted) to dodge the API's unique-email constraint. So re-runs never collide and don't pollute the shared catalog.

**Q31. How do you handle the cold start (first request up to ~60s)?**
**A.** `BaseTest` sets generous HTTP timeouts, and `warmUpAndLogin` has a larger dedicated budget to absorb the wake-up before the real tests run.

**Q32. How would you run this in CI?**
**A.** `mvn clean test` in a pipeline (GitHub Actions/Jenkins) with a JDK 21 setup step; publish the `target/surefire-reports` as artifacts. For the flaky host, the skip-on-outage design keeps CI green on infra blips while still failing on real defects. (Could add a scheduled warm-up ping before the job.)

---

## 6. Testing mindset & findings

**Q33. The task said "identify gaps" — what did you find?**
**A.** Documented in `FINDINGS.md`. Highlights: `dateOfBirth` truncated to the **year**; inconsistent DOB format across endpoints; login error uses `error` not the documented `message`; undocumented `accountExpirationDate`; and most seriously, **invalid input crashes the service** (availability defect) rather than returning a clean `400`.

**Q34. If `dateOfBirth` is buggy, why don't tests assert its exact value?**
**A.** Deliberate: asserting a known-broken behaviour would either bake in the bug or make the suite red for a defect that's already reported. Instead the model keeps DOB as a raw `String`, tests avoid asserting it exactly, and the defect is captured in `FINDINGS.md` for developers.

**Q35. What types of testing did you (not) do, and why?**
**A.** Functional/positive + negative + end-to-end. **No** performance or security tests — the exercise explicitly excludes non-functional testing.

**Q36. What would you add with more time?**
**A.** JSON schema validation of responses, contract tests against the OpenAPI spec, boundary/validation cases (once the crash is fixed), data-driven tests across all admin accounts, and CI with reporting.

**Q37. How did you decide what to test?**
**A.** From the business requirements and the API contract: auth is mandatory on every employee endpoint, full CRUD must work, and the headline HR-admin journey must succeed end-to-end — then negative/edge cases around each.

---

## 7. Java / POJOs / Jackson

**Q38. What are the `model/` classes for?**
**A.** POJOs that mirror the API's JSON. Jackson serialises them into request bodies and deserialises responses into them, giving type-safe, readable test data instead of hand-built JSON strings.

**Q39. What do `@JsonInclude(NON_NULL)` and `@JsonIgnoreProperties(ignoreUnknown=true)` do?**
**A.** `NON_NULL` omits null fields from the request JSON (so optional fields we don't set aren't sent). `ignoreUnknown` means extra/undocumented response fields (like `accountExpirationDate`) don't break deserialisation.

**Q40. Why a builder on `Employee`?**
**A.** Readability — tests construct employees fluently (`Employee.builder().firstName(..).build()`) and can build partial objects for negative cases without a pile of constructors.

**Q41. Why is `dateOfBirth` a `String` and not a date type?**
**A.** Because the API is inconsistent about its format (year vs full ISO date-time). Binding to `LocalDate` would fail deserialisation; a raw `String` is robust and the inconsistency is documented as a finding.

---

## 8. Rapid-fire one-liners

| Q | A |
|---|---|
| Latest stable Maven? | 3.9.16 (Maven 4 is still RC). |
| Is `<modelVersion>4.0.0</modelVersion>` the Maven version? | No — the POM schema version. |
| Where's the Maven tool version? | Not in the POM; from `mvn -version` (installed binary). |
| What is `pom.xml`? | Project Object Model — describes & builds the project. |
| Project version? | `<version>1.0.0</version>` (the artifact's own version). |
| Test runner? | JUnit 5 (Jupiter), executed by Surefire. |
| HTTP library? | REST-assured. |
| Assertions? | Hamcrest (`assertThat` + matchers). |
| Fake data? | Datafaker, with UUID-salted unique emails. |
| Auth model? | JWT bearer token from `POST /hr/login`. |
| How many tests? | 32 (some `@Disabled` on the live host). |
| Failure vs skip? | Failure = API defect; skip = infra outage. |
| Run everything? | `mvn clean test`. |
| Ship rule? | `mvn clean` first; no exe/sh/bat/bin/json in the zip. |

---

*See also: [README.md](README.md) for full docs and [FINDINGS.md](FINDINGS.md) for the detailed defect log.*
