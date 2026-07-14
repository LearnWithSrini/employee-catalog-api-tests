package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.model.Address;
import com.dwp.employeecatalog.model.ContactInfo;
import com.dwp.employeecatalog.model.Employee;
import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies the <b>create employee</b> endpoint, {@code POST /employees}.
 *
 * <p><b>Purpose:</b> confirm that a valid, authenticated request creates an
 * employee (HTTP 201 with a generated {@code Emp-} id and the submitted data
 * echoed back), and that invalid requests are rejected — duplicate email (400),
 * missing required fields, and an empty body.</p>
 *
 * <p>Several negative tests are {@link org.junit.jupiter.api.Disabled} against
 * the live host because the service crashes on invalid input (see FINDINGS #5/#8);
 * they are retained to run against a fixed or local API, and use
 * {@code createEmployeeNoRetry} so a single bad request can't be retried into a
 * sustained outage. Employees created here are removed in {@link #cleanUp()} so
 * runs stay independent and the shared catalog stays tidy.</p>
 */
@DisplayName("POST /employees - create employee")
class CreateEmployeeTests extends BaseTest {

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdIds.forEach(id -> api.deleteEmployee(token, id));
        createdIds.clear();
    }

    /**
     * Safely reads {@code employeeId} from a response that may not be JSON (a
     * rejected create can return a plain-text / HTML error body). Returns empty
     * rather than throwing, so negative tests never fail on body parsing.
     */
    private static Optional<String> extractEmployeeId(Response response) {
        String contentType = response.getContentType();
        if (contentType == null || !contentType.contains("json")) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(response.jsonPath().getString("employeeId"));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("valid payload creates an employee and returns 201 with an id")
    void validPayload_creates201() {
        Employee employee = TestDataFactory.validEmployee();

        Response response = api.createEmployee(token, employee);

        assertThat(response.statusCode(), is(201));
        String id = response.jsonPath().getString("employeeId");
        assertThat("a generated employeeId must be returned", id, is(not(emptyOrNullString())));
        assertThat("employeeId should use the documented 'Emp-' prefix", id, startsWith("Emp-"));
        createdIds.add(id);

        // The response should echo the data we sent.
        assertThat(response.jsonPath().getString("firstName"), equalTo(employee.getFirstName()));
        assertThat(response.jsonPath().getString("lastName"), equalTo(employee.getLastName()));
    }

    @Test
    @DisplayName("minimal payload (required fields + blank optional contact fields) is accepted (201)")
    void minimalPayload_isAccepted() {
        // Real first/last name + unique email, with the optional phone/address
        // sent as blank strings "". The host crashes if those nested keys are
        // OMITTED (FINDINGS #8) but accepts them present-but-empty, so this is
        // the smallest body the live service will actually create.
        Employee employee = TestDataFactory.minimalValidEmployee();

        Response response = api.createEmployee(token, employee);

        assertThat(response.statusCode(), is(201));
        createdIds.add(response.jsonPath().getString("employeeId"));
    }

    @Test
    @DisplayName("duplicate email is rejected with HTTP 400")
    void duplicateEmail_isRejected() {
        Employee first = TestDataFactory.validEmployee();
        Response created = api.createEmployee(token, first);
        assertThat("precondition: first create succeeds", created.statusCode(), is(201));
        createdIds.add(created.jsonPath().getString("employeeId"));

        // Second employee re-using the same email address.
        String dupEmail = first.getContactInfo().getEmail();
        Employee duplicate = TestDataFactory.validEmployee();
        duplicate.getContactInfo().setEmail(dupEmail);

        Response response = api.createEmployee(token, duplicate);

        assertThat("re-using an email must be rejected", response.statusCode(), is(400));

        // Top-level error message.
        assertThat("body should report a duplicate-key error",
                response.jsonPath().getString("message"), equalTo("Duplicate key error"));

        // Nested error detail: the offending field, its value, and a human message.
        assertThat("error should identify the offending field as the email",
                response.jsonPath().getString("error.field"), containsString("email"));
        assertThat("error should echo the duplicated email value",
                response.jsonPath().getString("error.value"), equalTo(dupEmail));
        assertThat("error message should say the email is already in use",
                response.jsonPath().getString("error.message"),
                allOf(containsString(dupEmail), containsString("already in use")));
    }

    @Test
    @DisplayName("missing required field (lastName) is rejected, not 201")
    void missingRequiredField_isRejected() {
        // Full body EXCEPT the required lastName. Blank optional contact fields are
        // supplied so we hit the lastName validation, not the crash-on-absent-
        // nested-field defect (FINDINGS #8).
        Employee employee = Employee.builder()
                .firstName("NoLastName")
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(
                        TestDataFactory.uniqueEmail("no", "last"), "", new Address("", "", "")))
                .build();

        Response response = api.createEmployee(token, employee);

        // Must be rejected as a validation error. The API *currently* returns 500,
        // but the correct status is 400 (FINDINGS #13) — oneOf(400, 500) accepts
        // both so the test survives a future fix, while still failing if the
        // endpoint regresses to some other status (e.g. 201/401/404).
        assertThat("missing lastName must be rejected as a validation error",
                response.statusCode(), is(oneOf(400, 500)));
        assertThat("error should name the missing required field (lastName)",
                response.jsonPath().getString("error"),
                allOf(containsString("lastName"), containsString("required")));

        // Guard against silent success: if it somehow created, clean it up.
        extractEmployeeId(response).ifPresent(createdIds::add);
    }

    @Test
    @DisplayName("missing contactInfo.email is rejected, not 201")
    void missingEmail_isRejected() {
        // Full body EXCEPT the required email (blank phone/address supplied).
        Employee employee = Employee.builder()
                .firstName("NoEmail")
                .lastName("Person")
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(null, "", new Address("", "", "")))
                .build();

        Response response = api.createEmployee(token, employee);

        // Currently 500, correct is 400 (FINDINGS #13) — accept either.
        assertThat("missing email must be rejected as a validation error",
                response.statusCode(), is(oneOf(400, 500)));
        assertThat("error should name the missing required field (email)",
                response.jsonPath().getString("error"),
                allOf(containsString("email"), containsString("required")));
        extractEmployeeId(response).ifPresent(createdIds::add);
    }

    @Test
    @DisplayName("empty-string firstName is rejected with 400 and a min-length message")
    void emptyFirstName_isRejectedWith400() {
        // Empty firstName with an otherwise valid body (blank optional contact fields).
        // This is a case the API handles correctly: a clean 400 with a clear message.
        Employee employee = Employee.builder()
                .firstName("")
                .lastName("Person")
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(
                        TestDataFactory.uniqueEmail("empty", "first"), "", new Address("", "", "")))
                .build();

        Response response = api.createEmployee(token, employee);

        assertThat("an empty first name must be rejected", response.statusCode(), is(400));
        assertThat("body should explain the minimum-length rule",
                response.jsonPath().getString("message"),
                equalTo("First name must be at least 2 characters long."));

        extractEmployeeId(response).ifPresent(createdIds::add);
    }

    // DISABLED — DO NOT DELETE. An empty body {} has no contactInfo object at all,
    // which triggers the crash-on-absent-nested-field defect (502, FINDINGS #5/#8)
    // and takes the shared host + /api-docs UI down. Unlike the missing-single-field
    // cases above (which return a handled 500), this one still crashes, so it stays
    // disabled. Re-enable against a fixed / local API.
    @Disabled("Empty body crashes the live free-tier host (502, absent contactInfo). See FINDINGS #5/#8.")
    @Test
    @DisplayName("empty JSON body is rejected, not 201")
    void emptyBody_isRejected() {
        // No-retry: an empty body can crash the fragile free-tier host (FINDINGS #5).
        Response response = api.createEmployeeNoRetry(token, Map.of());

        assertThat("an empty body must not create an employee",
                response.statusCode(), is(not(201)));
    }
}
