# Status-Code Coverage Matrix

Which test validates each documented status code, per endpoint. Statuses are
those declared in the API's OpenAPI spec (plus `403`, observed for an invalid
token). Test names are the JUnit methods; the class is shown in the last column.

Legend: ✅ asserted · ⚠️ exercised but not asserted exclusively · ❌ not covered
(see notes) · n/a not applicable for this endpoint.

This matrix maps tests that assert an HTTP **status**. A few methods assert a
non-status precondition and so don't appear here — notably
`EmployeeLifecycleJourneyTest.step1_adminIsAuthenticated`, which checks a bearer
token exists before the journey begins. Every other `@Test` in the suite maps to
at least one row below.

---

## POST /hr/login

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 200 | ✅ | `validCredentials_returnToken` | `LoginTests` |
| 401 | ✅ | `validUsername_invalidPassword_isRejected`, `invalidUsername_validPassword_isRejected`, `emptyStringUsername_isRejected`, `nullUsername_isRejected`, `validUsername_emptyStringPassword_isRejected`, `validUsername_blankPassword_isRejected`, `wrongCasePassword_isRejected` | `LoginTests` |
| 500 | ✅ | `validUsername_nullPassword_isRejected` (defect, FINDINGS #10) | `LoginTests` |

## POST /employees

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 201 | ✅ | `validPayload_creates201`, `duplicateFirstName_isAllowed`, `minimalPayload_isAccepted` | `CreateEmployeeTests` |
| 201 | ✅ | `create_withValidToken_isAuthorized` | `AuthorizationTests` |
| 201 | ✅ | `step2_addEmployee` | `EmployeeLifecycleJourneyTest` |
| 400 | ✅ | `duplicateEmail_isRejected`, `emptyFirstName_isRejectedWith400` | `CreateEmployeeTests` |
| 401 | ✅ | `create_withoutToken_isUnauthorized` | `AuthorizationTests` |
| 500 | ⚠️ | `missingRequiredField_isRejected`, `missingEmail_isRejected` — assert `oneOf(400,500)`; live host returns 500 (should be 400, FINDINGS #13) | `CreateEmployeeTests` |

## GET /employees

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 200 | ✅ | `returnsOkAndArray`, `everyEmployeeHasId`, `newlyCreatedEmployeeIsListed` | `GetAllEmployeesTests` |
| 200 | ✅ | `getAll_withValidToken_isAuthorized` | `AuthorizationTests` |
| 200 | ✅ | `step4_employeeAppearsInCatalog` | `EmployeeLifecycleJourneyTest` |
| 401 | ✅ | `getAll_withoutToken_isUnauthorized` | `AuthorizationTests` |
| 403 | ✅ | `garbageToken_isRejected` (invalid token) | `AuthorizationTests` |
| 500 | ❌ | not deterministically reproducible — see notes | — |

## GET /employees/{id}

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 200 | ✅ | `existingId_returnsEmployee` | `GetEmployeeByIdTests` |
| 200 | ✅ | `getById_withValidToken_isAuthorized` | `AuthorizationTests` |
| 200 | ✅ | `step3_employeeIsRetrievable` | `EmployeeLifecycleJourneyTest` |
| 401 | ✅ | `getById_withoutToken_isUnauthorized` | `AuthorizationTests` |
| 404 | ✅ | `unknownId_returns404` | `GetEmployeeByIdTests` |
| 404 | ✅ | `deleteExisting_removesRecord` (read-back after delete) | `DeleteEmployeeTests` |
| 404 | ✅ | `step7_employeeIsGone` | `EmployeeLifecycleJourneyTest` |
| 500 | ❌ | not deterministically reproducible — see notes | — |

## PUT /employees/{id}

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 200 | ✅ | `updateExisting_persistsChange` | `UpdateEmployeeTests` |
| 200 | ✅ | `update_withValidToken_isAuthorized` | `AuthorizationTests` |
| 200 | ✅ | `step5_amendEmployee` | `EmployeeLifecycleJourneyTest` |
| 401 | ✅ | `update_withoutToken_isUnauthorized` | `AuthorizationTests` |
| 404 | ✅ | `updateUnknown_returns404` | `UpdateEmployeeTests` |
| 500 | ❌ | not deterministically reproducible — see notes | — |

## DELETE /employees/{id}

| Status | Cov | Test(s) | Class |
|--------|:---:|---------|-------|
| 200 | ✅ | `deleteExisting_removesRecord` | `DeleteEmployeeTests` |
| 200 | ✅ | `delete_withValidToken_isAuthorized` | `AuthorizationTests` |
| 200 | ✅ | `step6_removeEmployee` | `EmployeeLifecycleJourneyTest` |
| 401 | ✅ | `delete_withoutToken_isUnauthorized` | `AuthorizationTests` |
| 404 | ✅ | `deleteUnknown_returns404` | `DeleteEmployeeTests` |
| 500 | ❌ | not deterministically reproducible — see notes | — |

---

## Summary

| Status | Covered? |
|--------|----------|
| 200 / 201 | ✅ every endpoint |
| 400 | ✅ (POST /employees) |
| 401 | ✅ login + all five employee endpoints |
| 403 | ✅ (GET /employees, invalid token) |
| 404 | ✅ (GET by id, PUT, DELETE) |
| 500 | ✅ where reproducible (login, create); ❌ elsewhere (see notes) |

## Notes on `500`

`500 Internal server error` is documented for every endpoint, but a server error
can only be **provoked**, not requested:

- **Covered** where the live implementation reliably returns it for a specific
  input: login with a `null` password (FINDINGS #10), and create with a missing
  required field (FINDINGS #13).
- **Not covered** for `GET /employees`, `GET /employees/{id}`, `PUT` and
  `DELETE`: there is no client input that deterministically forces a `500` on
  these read/mutate paths. Reproducing it would require crashing the server or
  injecting a fault — i.e. the destructive / non-functional testing the exercise
  explicitly excludes. This is a **conscious, documented gap**, not an oversight.

## Related docs

- **[Employee-Catalog-API-Service-Requirements.md](Employee-Catalog-API-Service-Requirements.md)** — the requirements each status maps to.
- **[FINDINGS.md](FINDINGS.md)** — defects behind the `500`/status anomalies referenced above.
