package com.dwp.employeecatalog.tests;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
        Employee duplicate = TestDataFactory.validEmployee();
        duplicate.getContactInfo().setEmail(first.getContactInfo().getEmail());

        Response response = api.createEmployee(token, duplicate);

        assertThat("re-using an email must be rejected", response.statusCode(), is(400));
        // The API reports the duplicated field as "contactInfo.email".
        assertThat("error should identify the offending field as the email",
                response.jsonPath().getString("error.field"), containsString("email"));
    }

    // DISABLED — DO NOT DELETE. This test sends a malformed payload (missing
    // required field). The live service has a defect (FINDINGS #5) where invalid
    // input throws an unhandled error that CRASHES the single free-tier instance,
    // taking the API *and* the /api-docs UI down until Render restarts it.
    // Kept for when the API is fixed / run against a local build; disabled for now
    // so the suite doesn't take the shared host down.
    @Disabled("Crashes the live free-tier host (invalid input -> unhandled 5xx). See FINDINGS #5.")
    @Test
    @DisplayName("missing required field (lastName) is rejected, not 201")
    void missingRequiredField_isRejected() {
        // Build a body deliberately missing the required lastName.
        Employee employee = Employee.builder()
                .firstName("NoLastName")
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(
                        TestDataFactory.uniqueEmail("no", "last"), null, null))
                .build();

        // No-retry: an invalid payload can crash the fragile free-tier host
        // (FINDINGS #5); retrying would only re-trigger the crash. We send once.
        Response response = api.createEmployeeNoRetry(token, employee);

        // A well-behaved API should reject this with a 4xx (ideally 400).
        assertThat("creating without a required field must not succeed with 201",
                response.statusCode(), is(not(201)));

        // Guard against silent success: if it somehow created, clean it up.
        extractEmployeeId(response).ifPresent(createdIds::add);
    }

    // DISABLED — DO NOT DELETE. Malformed payload (missing required email); may
    // crash the live free-tier host (unhandled 5xx on invalid input, FINDINGS #5),
    // taking the API + /api-docs UI down. Re-enable against a fixed / local API.
    @Disabled("Crashes the live free-tier host (invalid input -> unhandled 5xx). See FINDINGS #5.")
    @Test
    @DisplayName("missing contactInfo.email is rejected, not 201")
    void missingEmail_isRejected() {
        Employee employee = Employee.builder()
                .firstName("NoEmail")
                .lastName("Person")
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(null, "+441234567890", null))
                .build();

        // No-retry: see missingRequiredField_isRejected.
        Response response = api.createEmployeeNoRetry(token, employee);

        assertThat("creating without the required email must not succeed with 201",
                response.statusCode(), is(not(201)));
        extractEmployeeId(response).ifPresent(createdIds::add);
    }

    // DISABLED — DO NOT DELETE. Empty body +++++{} may crash the live free-tier host
    // (unhandled 5xx on invalid input, FINDINGS #5), taking the API + /api-docs UI
    // down. Re-enable against a fixed / local API.
    @Disabled("Crashes the live free-tier host (invalid input -> unhandled 5xx). See FINDINGS #5.")
    @Test
    @DisplayName("empty JSON body is rejected, not 201")
    void emptyBody_isRejected() {
        // No-retry: an empty body can crash the fragile free-tier host (FINDINGS #5).
        Response response = api.createEmployeeNoRetry(token, Map.of());

        assertThat("an empty body must not create an employee",
                response.statusCode(), is(not(201)));
    }
}
