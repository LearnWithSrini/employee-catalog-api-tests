package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.model.ContactInfo;
import com.dwp.employeecatalog.model.Employee;
import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@code PUT /employees/{id}}.
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
                    .contactInfo(new ContactInfo(
                            TestDataFactory.uniqueEmail("amended", "surname"), null, null))
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
                .contactInfo(new ContactInfo(
                        TestDataFactory.uniqueEmail("ghost", "missing"), null, null))
                .build();

        Response response = api.updateEmployee(token, TestDataFactory.nonExistentEmployeeId(), update);

        assertThat("updating a missing employee should yield 404",
                response.statusCode(), is(404));
    }
}
