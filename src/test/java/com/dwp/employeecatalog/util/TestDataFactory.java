package com.dwp.employeecatalog.util;

import com.dwp.employeecatalog.model.Address;
import com.dwp.employeecatalog.model.ContactInfo;
import com.dwp.employeecatalog.model.Employee;
import net.datafaker.Faker;

import java.util.Locale;
import java.util.UUID;

/**
 * Produces fake, non-real employee data so no genuine personal data is ever
 * sent to the service (per the exercise guidelines).
 *
 * <p>Emails are salted with a random token to guarantee uniqueness across runs,
 * because the API enforces a unique-email constraint and would otherwise return
 * a duplicate-key error on re-runs.</p>
 */
public final class TestDataFactory {

    private static final Faker FAKER = new Faker(Locale.UK);

    private TestDataFactory() {
    }

    /** A fully-populated, valid employee with a guaranteed-unique email. */
    public static Employee validEmployee() {
        String firstName = FAKER.name().firstName();
        String lastName = FAKER.name().lastName();

        Address address = new Address(
                FAKER.address().streetAddress(),
                FAKER.address().city(),
                FAKER.address().postcode());

        ContactInfo contactInfo = new ContactInfo(
                uniqueEmail(firstName, lastName),
                "+44" + FAKER.number().digits(10),
                address);

        return Employee.builder()
                .firstName(firstName)
                .lastName(lastName)
                .dateOfBirth(FAKER.timeAndDate().birthday(21, 65).toString())
                .contactInfo(contactInfo)
                .build();
    }

    /**
     * A minimal employee the live host accepts: required fields set to real
     * values, plus a unique email, with the <b>optional</b> contact fields
     * (phone / address) present but <b>blank ("")</b>.
     *
     * <p>Empty strings are used deliberately rather than omitting phone/address:
     * the API crashes when those nested keys are absent (FINDINGS #8), but accepts
     * them when present-but-empty. firstName/lastName cannot be blank — the API
     * rejects an empty name with 400 (FINDINGS #9) — so they carry real values.</p>
     */
    public static Employee minimalValidEmployee() {
        String firstName = FAKER.name().firstName();
        String lastName = FAKER.name().lastName();
        return Employee.builder()
                .firstName(firstName)
                .lastName(lastName)
                .dateOfBirth("1990-01-01")
                .contactInfo(new ContactInfo(
                        uniqueEmail(firstName, lastName),
                        "",                        // blank phone
                        new Address("", "", "")))  // blank street/town/postCode
                .build();
    }

    /** Unique, clearly-synthetic email address. */
    public static String uniqueEmail(String firstName, String lastName) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        return (firstName + "." + lastName + "." + token + "@example.test")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.@]", "");
    }

    /** A well-formed employee id that is (practically) guaranteed not to exist. */
    public static String nonExistentEmployeeId() {
        return "Emp-" + UUID.randomUUID();
    }
}
