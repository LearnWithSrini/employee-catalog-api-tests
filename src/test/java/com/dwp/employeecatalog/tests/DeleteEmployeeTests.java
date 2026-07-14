package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Verifies the <b>delete employee</b> endpoint, {@code DELETE /employees/{id}}.
 *
 * <p><b>Purpose:</b> confirm that deleting an existing employee returns 200 and
 * the record is genuinely gone (a follow-up GET returns 404 — this represents an
 * employee leaving the organisation), and that deleting an unknown id returns
 * 404.</p>
 */
@DisplayName("DELETE /employees/{id} - remove employee")
class DeleteEmployeeTests extends BaseTest {

    @Test
    @DisplayName("deleting an existing employee returns 200 and the record is gone")
    void deleteExisting_removesRecord() {
        String id = api.createEmployeeAndReturnId(token, TestDataFactory.validEmployee());

        Response delete = api.deleteEmployee(token, id);
        assertThat(delete.statusCode(), is(200));

        // The employee should no longer be retrievable.
        Response afterDelete = api.getEmployeeById(token, id);
        assertThat("a deleted employee must not be retrievable",
                afterDelete.statusCode(), is(404));
        assertThat("404 body should carry the 'Employee not found' message",
                afterDelete.jsonPath().getString("message"), equalTo("Employee not found"));
    }

    @Test
    @DisplayName("deleting a non-existent employee returns HTTP 404")
    void deleteUnknown_returns404() {
        Response response = api.deleteEmployee(token, TestDataFactory.nonExistentEmployeeId());

        assertThat("deleting a missing employee should yield 404",
                response.statusCode(), is(404));
        assertThat("404 body should carry the 'Employee not found' message",
                response.jsonPath().getString("message"), equalTo("Employee not found"));
    }
}
