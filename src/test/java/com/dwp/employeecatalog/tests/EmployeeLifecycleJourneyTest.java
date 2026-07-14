package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.model.Employee;
import com.dwp.employeecatalog.util.TestDataFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The headline happy-path user journey required by the exercise:
 *
 * <p>An HR administrator logs in, <b>adds</b> a new employee, confirms the
 * employee exists (individually and in the catalog), <b>amends</b> the record,
 * and finally <b>removes</b> the employee when they leave the organisation.</p>
 *
 * <p>The steps are ordered and share state so they read as one coherent
 * end-to-end story against the live service.</p>
 */
@DisplayName("End-to-end journey: HR admin adds, amends and removes an employee")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmployeeLifecycleJourneyTest extends BaseTest {

    private static Employee newHire;
    private static String employeeId;

    @Test
    @Order(1)
    @DisplayName("Step 1 - HR admin is authenticated with a bearer token")
    void step1_adminIsAuthenticated() {
        // The token is obtained once in BaseTest#authenticateOnce.
        assertThat("HR admin must hold a valid token before managing employees",
                token, is(not(equalTo(null))));
        assertThat(token.isBlank(), is(false));
    }

    @Test
    @Order(2)
    @DisplayName("Step 2 - add a new employee to the catalog")
    void step2_addEmployee() {
        newHire = TestDataFactory.validEmployee();

        Response response = api.createEmployee(token, newHire);

        assertThat("new hire should be created", response.statusCode(), is(201));
        employeeId = response.jsonPath().getString("employeeId");
        assertThat(employeeId, is(not(equalTo(null))));
        assertThat(response.jsonPath().getString("firstName"),
                equalTo(newHire.getFirstName()));
    }

    @Test
    @Order(3)
    @DisplayName("Step 3 - the new employee is retrievable by id")
    void step3_employeeIsRetrievable() {
        Response response = api.getEmployeeById(token, employeeId);

        assertThat(response.statusCode(), is(200));
        assertThat(response.jsonPath().getString("employeeId"), equalTo(employeeId));
        assertThat(response.jsonPath().getString("contactInfo.email"),
                equalTo(newHire.getContactInfo().getEmail()));
    }

    @Test
    @Order(4)
    @DisplayName("Step 4 - the new employee appears in the full catalog")
    void step4_employeeAppearsInCatalog() {
        Response response = api.getAllEmployees(token);

        assertThat(response.statusCode(), is(200));
        assertThat("catalog should contain the new hire",
                response.jsonPath().getList("employeeId"), hasItem(employeeId));
    }

    @Test
    @Order(5)
    @DisplayName("Step 5 - amend the employee's details (change of circumstances)")
    void step5_amendEmployee() {
        // Complete valid body (reuse the new hire's required dateOfBirth/contact,
        // change only the first name). A partial body would omit required fields
        // and trigger the server's crash-on-invalid-input defect (FINDINGS #5).
        Employee amendment = Employee.builder()
                .firstName("Amended")
                .lastName(newHire.getLastName())
                .dateOfBirth(newHire.getDateOfBirth())
                .contactInfo(newHire.getContactInfo())
                .build();

        Response update = api.updateEmployee(token, employeeId, amendment);
        assertThat(update.statusCode(), is(200));

        Response fetched = api.getEmployeeById(token, employeeId);
        assertThat("amendment should persist",
                fetched.jsonPath().getString("firstName"), equalTo("Amended"));
    }

    @Test
    @Order(6)
    @DisplayName("Step 6 - remove the employee as they leave the organisation")
    void step6_removeEmployee() {
        Response delete = api.deleteEmployee(token, employeeId);
        assertThat(delete.statusCode(), is(200));
    }

    @Test
    @Order(7)
    @DisplayName("Step 7 - the removed employee can no longer be retrieved")
    void step7_employeeIsGone() {
        Response response = api.getEmployeeById(token, employeeId);
        assertThat("a departed employee must not be retrievable",
                response.statusCode(), is(404));
        assertThat("404 body should carry the 'Employee not found' message",
                response.jsonPath().getString("message"), equalTo("Employee not found"));
    }
}
