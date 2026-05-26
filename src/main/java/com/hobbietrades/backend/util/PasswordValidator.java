package com.hobbietrades.backend.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PasswordValidator {

    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

    private PasswordValidator() {}

    public static List<String> validate(String password) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.length() < 8) {
            errors.add("Password must be at least 8 characters.");
        }
        if (password == null || !UPPERCASE.matcher(password).find()) {
            errors.add("Password must include at least one capital letter.");
        }
        if (password == null || !SPECIAL.matcher(password).find()) {
            errors.add("Password must include at least one special symbol (e.g. ! @ # $).");
        }
        return errors;
    }

    public static boolean isValid(String password) {
        return validate(password).isEmpty();
    }
}
