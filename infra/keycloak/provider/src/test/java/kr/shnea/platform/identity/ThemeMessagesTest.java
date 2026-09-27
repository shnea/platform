package kr.shnea.platform.identity;

import org.junit.jupiter.api.Test;
import org.keycloak.theme.beans.LinkExpirationFormatterMethod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ThemeMessagesTest {
    private Properties messages(String type, String language) throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../themes/platform", type,
                "messages/messages_" + language + ".properties"))) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void expirationDoesNotRepeatTheNumber() throws Exception {
        var formatter = new LinkExpirationFormatterMethod(messages("email", "ko"), Locale.KOREAN);
        assertEquals("5 분", formatter.exec(List.of(5)));
        assertEquals("1 시간", formatter.exec(List.of(60)));
        assertEquals("1 일", formatter.exec(List.of(1440)));
    }

    @Test
    void loginAndEmailsOmitInternalIdentifiersButPreserveActions() throws Exception {
        String realm = "p-0e6f318fdf5c45749b80a277ef5b1f20";
        String username = "opaque-social-subject-1234";
        String link = "https://platform.example/action?key=signed-token";
        for (String language : List.of("ko", "en")) {
            var login = messages("login", language);
            for (String key : List.of("loginTitle", "loginTitleHtml")) {
                assertFalse(MessageFormat.format(login.getProperty(key), realm).contains(realm));
            }
            var email = messages("email", language);
            for (String suffix : List.of("Body", "BodyHtml")) {
                for (String prefix : List.of("emailVerification", "passwordReset", "executeActions",
                        "emailUpdateConfirmation", "identityProviderLink")) {
                    Object[] args = switch (prefix) {
                        case "executeActions" -> new Object[]{link, 5, realm, "UPDATE_PASSWORD", "5 분"};
                        case "emailUpdateConfirmation" -> new Object[]{link, "new@example.invalid", realm, "5 분"};
                        case "identityProviderLink" -> new Object[]{"Google", realm, username, link, 5, "5 분"};
                        default -> new Object[]{link, 5, realm, "5 분"};
                    };
                    String rendered = MessageFormat.format(email.getProperty(prefix + suffix), args);
                    assertFalse(rendered.contains(realm), prefix);
                    assertFalse(rendered.contains(username), prefix);
                    assertTrue(rendered.contains(link), prefix);
                    assertTrue(rendered.contains("5 분"), prefix);
                    if (prefix.equals("executeActions")) assertTrue(rendered.contains("UPDATE_PASSWORD"));
                    if (prefix.equals("emailUpdateConfirmation")) assertTrue(rendered.contains("new@example.invalid"));
                    if (prefix.equals("identityProviderLink")) assertTrue(rendered.contains("Google"));
                }
            }
        }
    }
}
