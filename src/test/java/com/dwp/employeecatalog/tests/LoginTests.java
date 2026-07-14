package com.dwp.employeecatalog.tests;

import com.dwp.employeecatalog.util.ConfigManager;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Verifies the <b>authentication</b> endpoint, {@code POST /hr/login}.
 *
 * <p><b>Purpose:</b> confirm that valid HR-admin credentials return a JWT, and
 * that every category of bad credential is rejected without issuing a token:
 * wrong password, unknown username, and empty / blank / null values for the
 * username or password.</p>
 *
 * <p>The bad-credential fixtures are defined in {@code config.properties} with
 * self-describing keys (e.g. {@code login.validUsername_invalidPassword.*}); the
 * {@code null} cases can't be stored in a properties file and are passed
 * directly in code. Business requirement: only authorised HR administrators may
 * authenticate, and a successful login returns an authorisation token.</p>
 */
@DisplayName("POST /hr/login - authentication")
class LoginTests extends BaseTest {

    // ---------------------------------------------------------------------
    // Positive
    // ---------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("valid admin credentials return HTTP 200 and a token")
    void validCredentials_returnToken() {
        Response response = api.login(ConfigManager.adminUsername(), ConfigManager.adminPassword());

        assertThat("valid login should return 200", response.statusCode(), is(200));

        String jwt = response.jsonPath().getString("token");
        assertThat("a token must be issued on success", jwt, is(not(emptyOrNullString())));
        // The service issues a JWT (three dot-separated segments).
        assertThat("token should be a JWT with 3 segments", jwt.split("\\.").length, is(3));
    }

    // ---------------------------------------------------------------------
    // 1) Valid username, but WRONG password
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("valid username + invalid password is rejected (401, no token)")
    void validUsername_invalidPassword_isRejected() {
        Response response = api.login(
                ConfigManager.get("login.validUsername_invalidPassword.username"),
                ConfigManager.get("login.validUsername_invalidPassword.password"));

        assertRejectedWith401(response);
    }

    // ---------------------------------------------------------------------
    // 2) INVALID (unknown) username, but valid password
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("invalid (unknown) username + valid password is rejected (401, no token)")
    void invalidUsername_validPassword_isRejected() {
        Response response = api.login(
                ConfigManager.get("login.invalidUsername_validPassword.username"),
                ConfigManager.get("login.invalidUsername_validPassword.password"));

        assertRejectedWith401(response);
    }

    // ---------------------------------------------------------------------
    // 3) Username is EMPTY STRING / NULL
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("empty-string username is rejected (401, no token)")
    void emptyStringUsername_isRejected() {
        Response response = api.login(
                ConfigManager.get("login.emptyUsername_validPassword.username"),   // ""
                ConfigManager.get("login.emptyUsername_validPassword.password"));

        assertRejectedWith401(response);
    }

    @Test
    @DisplayName("null username is rejected (401, 'Invalid credentials', no token)")
    void nullUsername_isRejected() {
        Response response = api.login(null, ConfigManager.adminPassword());

        assertRejectedWith401(response);
    }

    // ---------------------------------------------------------------------
    // 4) Password is EMPTY STRING / BLANK / NULL
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("valid username + empty-string password is rejected (401, no token)")
    void validUsername_emptyStringPassword_isRejected() {
        Response response = api.login(
                ConfigManager.get("login.validUsername_emptyPassword.username"),
                ConfigManager.get("login.validUsername_emptyPassword.password"));   // ""

        assertRejectedWith401(response);
    }

    @Test
    @DisplayName("valid username + blank (single-space) password is rejected (401, no token)")
    void validUsername_blankPassword_isRejected() {
        Response response = api.login(
                ConfigManager.get("login.validUsername_blankPassword.username"),
                ConfigManager.get("login.validUsername_blankPassword.password"));   // " "

        assertRejectedWith401(response);
    }

    @Test
    @DisplayName("valid username + null password returns 500 (defect) and issues no token")
    void validUsername_nullPassword_isRejected() {
        Response response = api.login(ConfigManager.adminUsername(), null);

        // The live API mishandles a null password: instead of a clean 401/400 it
        // returns 500 "An error occurred during login" (see FINDINGS #10). This is
        // a characterization test that pins the current behaviour AND asserts the
        // security-critical fact that no token is issued. When the API is fixed to
        // reject null credentials with 401, switch this to assertRejectedWith401.
        assertRejectedWith500(response);
    }

    // ---------------------------------------------------------------------
    // Extra: password check is case-sensitive
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("correct password with wrong case is rejected (401)")
    void wrongCasePassword_isRejected() {
        Response response = api.login(ConfigManager.adminUsername(),
                ConfigManager.adminPassword().toUpperCase());

        // Case-sensitive: an upper-cased password is just a wrong password.
        assertRejectedWith401(response);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Asserts the (defective) 500 response the live API currently returns for a
     * null password: status 500, the {@code error} message, and no token. See
     * FINDINGS #10 — the correct behaviour would be a 401/400.
     */
    private static void assertRejectedWith500(Response response) {
        assertThat("null credential currently returns 500 (defect, FINDINGS #10)",
                response.statusCode(), is(500));
        assertThat("500 body should carry the login-error message",
                response.jsonPath().getString("error"), equalTo("An error occurred during login"));
        assertThat("no token may be leaked on a failed login",
                response.jsonPath().getString("token"), is(emptyOrNullString()));
    }

    /**
     * As {@link #assertRejected} but also asserts the documented 401 status and
     * the error message. NOTE: the message is returned under the {@code error}
     * field, not {@code message} as the API docs state (see FINDINGS #3), so we
     * read {@code error} to match the live contract.
     */
    private static void assertRejectedWith401(Response response) {
        assertThat("invalid credentials should return 401", response.statusCode(), is(401));
        assertThat("401 body should carry the 'Invalid credentials' message",
                response.jsonPath().getString("error"), equalTo("Invalid credentials"));
        assertThat("no token may be leaked on a failed login",
                response.jsonPath().getString("token"), is(emptyOrNullString()));
    }
}
