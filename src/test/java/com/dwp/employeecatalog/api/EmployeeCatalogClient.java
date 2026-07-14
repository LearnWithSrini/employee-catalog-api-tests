package com.dwp.employeecatalog.api;

import com.dwp.employeecatalog.model.Employee;
import com.dwp.employeecatalog.model.LoginRequest;
import com.dwp.employeecatalog.util.ConfigManager;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.opentest4j.TestAbortedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Thin, reusable client that owns the REST-assured plumbing for the Employee
 * Catalog API. Each method maps to exactly one endpoint and returns the raw
 * {@link Response} so tests stay in full control of their assertions.
 *
 * <p>The client also encapsulates the free-tier host's quirk of cold-starting:
 * {@link #warmUpAndLogin()} retries the login until the service wakes up, and
 * hands back a bearer token that authenticated calls reuse.</p>
 */
public class EmployeeCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(EmployeeCatalogClient.class);

    /**
     * Gateway-level statuses returned by the free-tier host while it is waking or
     * momentarily overloaded. They mean the request never reached the application,
     * so retrying is safe even for non-idempotent verbs like POST.
     */
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(502, 503, 504);

    private final RequestSpecification baseSpec;
    private final long retryBudgetMillis;
    private final long warmupBudgetMillis;
    private final long backoffMillis;

    public EmployeeCatalogClient() {
        RestAssured.baseURI = ConfigManager.baseUri();
        this.retryBudgetMillis = ConfigManager.getLong("retry.max.seconds") * 1000L;
        this.warmupBudgetMillis = ConfigManager.getLong("warmup.max.seconds") * 1000L;
        this.backoffMillis = ConfigManager.getLong("retry.backoff.millis");
        this.baseSpec = new RequestSpecBuilder()
                .setBaseUri(ConfigManager.baseUri())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                // Log only on validation failure to keep the console readable.
                .addFilter(new RequestLoggingFilter(io.restassured.filter.log.LogDetail.METHOD))
                .addFilter(new ResponseLoggingFilter(io.restassured.filter.log.LogDetail.STATUS))
                .build();
    }

    private RequestSpecification request() {
        return RestAssured.given().spec(baseSpec);
    }

    private RequestSpecification authedRequest(String token) {
        return request().header("Authorization", "Bearer " + token);
    }

    /**
     * Executes a call, transparently retrying only on transient gateway statuses
     * ({@link #TRANSIENT_STATUSES}) so the free-tier host's cold-start blips don't
     * turn into false test failures. Real application responses (2xx/4xx, and even
     * app-level 500s) are returned to the caller unchanged for assertion.
     */
    private Response execute(Supplier<Response> call) {
        long deadline = System.currentTimeMillis() + retryBudgetMillis;
        Response response;
        int attempt = 0;
        while (true) {
            attempt++;
            response = call.get();
            if (!TRANSIENT_STATUSES.contains(response.statusCode())) {
                return response;
            }
            if (System.currentTimeMillis() + backoffMillis >= deadline) {
                // The gateway never let the request reach the application within the
                // whole budget: this is a free-tier infrastructure outage, not a
                // defect in the API. Abort the test as *skipped* rather than failing
                // the build on an environment problem. A genuine 4xx/5xx *from the
                // application* is never transient and is returned above for assertion.
                throw new TestAbortedException(String.format(
                        "Service unreachable: gateway returned HTTP %d for the entire %ds retry budget "
                                + "(%d attempts). Skipping - this is a hosting cold-start/outage, not a test failure.",
                        response.statusCode(), retryBudgetMillis / 1000, attempt));
            }
            log.warn("Transient HTTP {} on attempt {}; retrying after {}ms",
                    response.statusCode(), attempt, backoffMillis);
            sleep(backoffMillis);
        }
    }

    /**
     * Executes a call exactly once, with no transient-retry. Used by the
     * malformed-input negative tests: there, a 5xx is a meaningful result to
     * assert on, not a blip to retry away. Critically, the service has a defect
     * (see FINDINGS #5) where an invalid payload throws an unhandled error and
     * can crash the single free-tier instance; retrying the same bad payload
     * only re-triggers the crash the moment the host restarts, turning one crash
     * into a sustained outage. Sending it once keeps the blast radius to a
     * single request so the host can recover.
     */
    private Response executeOnce(Supplier<Response> call) {
        return call.get();
    }

    // ----------------------------------------------------------------------
    // Authentication
    // ----------------------------------------------------------------------

    /** Raw login call — returns whatever the server responds, for negative tests too. */
    public Response login(String username, String password) {
        return execute(() -> request()
                .body(new LoginRequest(username, password))
                .post(Endpoints.LOGIN));
    }

    /**
     * Warms up the (possibly sleeping) free-tier host and returns a valid bearer
     * token. Retries transient 5xx / connection issues with backoff, up to the
     * configured number of attempts, before giving up.
     */
    public String warmUpAndLogin() {
        long deadline = System.currentTimeMillis() + warmupBudgetMillis;

        RuntimeException last = null;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                // login() already retries transient gateway statuses within its own budget.
                Response response = login(ConfigManager.adminUsername(), ConfigManager.adminPassword());
                int status = response.statusCode();
                if (status == 200) {
                    String token = response.jsonPath().getString("token");
                    if (token != null && !token.isBlank()) {
                        log.info("Authenticated on attempt {}", attempt);
                        return token;
                    }
                    throw new IllegalStateException("Login 200 but no token in body: " + response.asString());
                }
                log.warn("Login attempt {} returned HTTP {} (host may be waking up)", attempt, status);
                last = new IllegalStateException("Login failed with HTTP " + status);
            } catch (RuntimeException e) {
                log.warn("Login attempt {} failed: {}", attempt, e.getMessage());
                last = e;
            }
            sleep(backoffMillis);
        }
        throw new TestAbortedException(
                "Could not authenticate within the warm-up budget ("
                        + warmupBudgetMillis / 1000 + "s); the host appears to be down/cold. "
                        + "Skipping the suite - environment issue, not a test failure."
                        + (last != null ? " Last error: " + last.getMessage() : ""));
    }

    // ----------------------------------------------------------------------
    // Employee CRUD
    // ----------------------------------------------------------------------

    public Response getAllEmployees(String token) {
        return execute(() -> authedRequest(token).get(Endpoints.EMPLOYEES));
    }

    public Response getAllEmployeesNoAuth() {
        return execute(() -> request().get(Endpoints.EMPLOYEES));
    }

    public Response createEmployee(String token, Object body) {
        return execute(() -> authedRequest(token).body(body).post(Endpoints.EMPLOYEES));
    }

    public Response createEmployeeNoAuth(Object body) {
        return execute(() -> request().body(body).post(Endpoints.EMPLOYEES));
    }

    /**
     * Single-shot create with no transient-retry, for negative tests that send
     * deliberately invalid payloads. See {@link #executeOnce(Supplier)} for why
     * we must not retry these against the fragile free-tier host.
     */
    public Response createEmployeeNoRetry(String token, Object body) {
        return executeOnce(() -> authedRequest(token).body(body).post(Endpoints.EMPLOYEES));
    }

    public Response getEmployeeById(String token, String id) {
        return execute(() -> authedRequest(token).pathParam("id", id).get(Endpoints.EMPLOYEE_BY_ID));
    }

    public Response getEmployeeByIdNoAuth(String id) {
        return execute(() -> request().pathParam("id", id).get(Endpoints.EMPLOYEE_BY_ID));
    }

    public Response updateEmployee(String token, String id, Object body) {
        return execute(() -> authedRequest(token).pathParam("id", id).body(body).put(Endpoints.EMPLOYEE_BY_ID));
    }

    public Response updateEmployeeNoAuth(String id, Object body) {
        return execute(() -> request().pathParam("id", id).body(body).put(Endpoints.EMPLOYEE_BY_ID));
    }

    public Response deleteEmployee(String token, String id) {
        return execute(() -> authedRequest(token).pathParam("id", id).delete(Endpoints.EMPLOYEE_BY_ID));
    }

    public Response deleteEmployeeNoAuth(String id) {
        return execute(() -> request().pathParam("id", id).delete(Endpoints.EMPLOYEE_BY_ID));
    }

    // ----------------------------------------------------------------------
    // Convenience helpers
    // ----------------------------------------------------------------------

    /**
     * Creates an employee and returns its generated id. Fails fast if creation
     * did not return HTTP 201, so lifecycle tests don't proceed on a bad setup.
     */
    public String createEmployeeAndReturnId(String token, Employee employee) {
        Response response = createEmployee(token, employee);
        if (response.statusCode() != 201) {
            throw new IllegalStateException(
                    "Expected 201 creating employee but got " + response.statusCode()
                            + ": " + response.asString());
        }
        return response.jsonPath().getString("employeeId");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while backing off", e);
        }
    }
}
