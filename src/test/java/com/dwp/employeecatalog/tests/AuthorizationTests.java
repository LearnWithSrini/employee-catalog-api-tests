package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

/**
 * Cross-cutting authorisation checks — tests the auth requirement from
 * <b>both sides</b>.
 *
 * <p><b>Purpose:</b> the business requirement is that "all employee endpoints
 * must operate using a specified authorization token, formatted as
 * Bearer 'token'." This class verifies, for every employee endpoint, that:</p>
 * <ul>
 *   <li>a request with <b>no / an invalid</b> token is <b>rejected</b> (401/403); and</li>
 *   <li>a request with a <b>valid admin</b> token is <b>authorised</b> (accepted, not 401/403).</li>
 * </ul>
 *
 * <p>The valid-token token is obtained once in {@link BaseTest}. Employees created
 * by the positive write tests are cleaned up in {@link #cleanUp()}.</p>
 */
@DisplayName("Authorization - employee endpoints require a valid bearer token")
class AuthorizationTests extends BaseTest {

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdIds.forEach(id -> api.deleteEmployee(token, id));
        createdIds.clear();
    }

    // ---------------------------------------------------------------------
    // Negative — WITHOUT a valid token, every endpoint must be rejected
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /employees without a token is rejected (401)")
    void getAll_withoutToken_isUnauthorized() {
        Response response = api.getAllEmployeesNoAuth();
        assertThat(response.statusCode(), is(401));
    }

    @Test
    @DisplayName("GET /employees/{id} without a token is rejected (401)")
    void getById_withoutToken_isUnauthorized() {
        Response response = api.getEmployeeByIdNoAuth(TestDataFactory.nonExistentEmployeeId());
        assertThat(response.statusCode(), is(401));
    }

    @Test
    @DisplayName("POST /employees without a token is rejected (401)")
    void create_withoutToken_isUnauthorized() {
        Response response = api.createEmployeeNoAuth(TestDataFactory.validEmployee());
        assertThat(response.statusCode(), is(401));
    }

    @Test
    @DisplayName("PUT /employees/{id} without a token is rejected (401)")
    void update_withoutToken_isUnauthorized() {
        Response response = api.updateEmployeeNoAuth(
                TestDataFactory.nonExistentEmployeeId(), TestDataFactory.validEmployee());
        assertThat(response.statusCode(), is(401));
    }

    @Test
    @DisplayName("DELETE /employees/{id} without a token is rejected (401)")
    void delete_withoutToken_isUnauthorized() {
        Response response = api.deleteEmployeeNoAuth(TestDataFactory.nonExistentEmployeeId());
        assertThat(response.statusCode(), is(401));
    }

    @Test
    @DisplayName("a malformed / garbage token is rejected (401/403)")
    void garbageToken_isRejected() {
        Response response = api.getAllEmployees("not-a-real-jwt-token");
        assertThat("a bogus token must not be accepted",
                response.statusCode(), is(oneOf(401, 403)));
    }

    // ---------------------------------------------------------------------
    // Positive — WITH a valid admin token, every endpoint must be authorised
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GET /employees with a valid admin token is authorised (200)")
    void getAll_withValidToken_isAuthorized() {
        Response response = api.getAllEmployees(token);

        assertThat("valid admin token must not be rejected as unauthorised",
                response.statusCode(), is(not(oneOf(401, 403))));
        assertThat(response.statusCode(), is(200));
    }

    @Test
    @DisplayName("POST /employees with a valid admin token is authorised (201)")
    void create_withValidToken_isAuthorized() {
        Response response = api.createEmployee(token, TestDataFactory.validEmployee());

        assertThat("valid admin token must not be rejected as unauthorised",
                response.statusCode(), is(not(oneOf(401, 403))));
        assertThat(response.statusCode(), is(201));
        createdIds.add(response.jsonPath().getString("employeeId"));
    }

    @Test
    @DisplayName("GET /employees/{id} with a valid admin token is authorised (200)")
    void getById_withValidToken_isAuthorized() {
        String id = api.createEmployeeAndReturnId(token, TestDataFactory.validEmployee());
        createdIds.add(id);

        Response response = api.getEmployeeById(token, id);

        assertThat("valid admin token must not be rejected as unauthorised",
                response.statusCode(), is(not(oneOf(401, 403))));
        assertThat(response.statusCode(), is(200));
    }

    @Test
    @DisplayName("PUT /employees/{id} with a valid admin token is authorised (200)")
    void update_withValidToken_isAuthorized() {
        String id = api.createEmployeeAndReturnId(token, TestDataFactory.validEmployee());
        createdIds.add(id);

        // Full valid body (avoids the crash-on-invalid-input defect, FINDINGS #5).
        Response response = api.updateEmployee(token, id, TestDataFactory.validEmployee());

        assertThat("valid admin token must not be rejected as unauthorised",
                response.statusCode(), is(not(oneOf(401, 403))));
        assertThat(response.statusCode(), is(200));
    }

    @Test
    @DisplayName("DELETE /employees/{id} with a valid admin token is authorised (200)")
    void delete_withValidToken_isAuthorized() {
        String id = api.createEmployeeAndReturnId(token, TestDataFactory.validEmployee());

        Response response = api.deleteEmployee(token, id);

        assertThat("valid admin token must not be rejected as unauthorised",
                response.statusCode(), is(not(oneOf(401, 403))));
        assertThat(response.statusCode(), is(200));
        // Deleted successfully — no cleanup needed.
    }
}
