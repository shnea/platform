package kr.shnea.platform.project;

import java.util.regex.Pattern;

record AuthenticationPolicy(boolean loginWithEmail, boolean verifyEmail, boolean resetPasswordAllowed,
                            int passwordMinLength, boolean passwordPolicyEditable,
                            boolean emailActionsAvailable, String revision) {
    static final String DEFAULT_PASSWORD_POLICY = "length(12) and maxLength(128)";
    private static final Pattern MANAGED_PASSWORD_POLICY = Pattern.compile("length\\((\\d{1,3})\\)(?: and maxLength\\(128\\))?");

    // Do not replace password rules entered directly in Keycloak with a weaker policy.
    static int minimum(String policy) {
        if (policy == null || policy.isBlank()) return 0;
        var match = MANAGED_PASSWORD_POLICY.matcher(policy);
        return match.matches() ? Integer.parseInt(match.group(1)) : -1;
    }
}
