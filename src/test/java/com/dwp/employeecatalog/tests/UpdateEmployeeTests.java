package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.model.Employee;
import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the <b>update employee</b> endpoint, {@code PUT /employees/{id}}.
 *
 * <p><b>Purpose:</b> confirm that amending an existing employee returns 200 and
 * the change is actually persisted (read back via GET), and that updating an
 * unknown id returns 404. Updates deliberately send a <em>complete</em> valid
 * body, because a partial body would omit required fields and trigger the
 * server's crash-on-invalid-input defect (see FINDINGS #5).</p>
 */
@DisplayName("PUT /employees/{id} - update employee")
class UpdateEmployeeTests extends BaseTest {

    @Test
    @DisplayName("updating an existing employee returns 200 and persists the change")
    void updateExisting_persistsChange() {
        String id = api.createEmployeeAndReturnId(token, TestDataFactory.validEmployee());
        try {
            // Send a COMPLETE valid body. A partial update (name only) omits the
            // required dateOfBirth / contactInfo.email and triggers the server's
            // crash-on-invalid-input defect (FINDINGS #5) -> a storm of 502s. A
            // real "amend" supplies the full record, so this stays a clean
            // happy-path test and doesn't take the host down.
            Employee update = Employee.builder()
                    .firstName("Amended")
                    .lastName("Surname")
                    .dateOfBirth("1990-01-01")
                    .contactInfo(TestDataFactory.blankContactInfo(
                            TestDataFactory.uniqueEmail("amended", "surname")))
                    .build();

            Response response = api.updateEmployee(token, id, update);
            assertThat(response.statusCode(), is(200));

            // Confirm the change actually persisted by reading the record back.
            Response fetched = api.getEmployeeById(token, id);
            assertThat(fetched.statusCode(), is(200));
            assertThat("first name should be updated",
                    fetched.jsonPath().getString("firstName"), equalTo("Amended"));
            assertThat("last name should be updated",
                    fetched.jsonPath().getString("lastName"), equalTo("Surname"));
        } finally {
            api.deleteEmployee(token, id);
        }
    }

    @Test
    @DisplayName("updating a non-existent employee returns HTTP 404")
    void updateUnknown_returns404() {
        // Full valid body so we exercise the not-found path, not the server's
        // crash-on-invalid-input defect (FINDINGS #5).
        Employee update = Employee.builder()
                .firstName("Ghost")
                .lastName("Missing")
                .dateOfBirth("1990-01-01")
                .contactInfo(TestDataFactory.blankContactInfo(
                        TestDataFactory.uniqueEmail("ghost", "missing")))
                .build();

        Response response = api.updateEmployee(token, TestDataFactory.nonExistentEmployeeId(), update);

        assertThat("updating a missing employee should yield 404",
                response.statusCode(), is(404));
        assertThat("404 body should carry the 'Employee not found' message",
                response.jsonPath().getString("message"), equalTo("Employee not found"));
    }
}
