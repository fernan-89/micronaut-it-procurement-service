package com.thinklab.infrastructure.config;

import io.micronaut.context.event.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LegacyPolicyVariableWarningTest {

    private static final String VALUE = "0b6e4a3e-0000-4000-8000-000000000001";

    @Test
    @DisplayName("a set variable is warned about, naming it and how to replace it, and never its value")
    void warnsWhenSet() {
        var warning = new LegacyPolicyVariableWarning(name -> LegacyPolicyVariableWarning.VARIABLE.equals(name) ? VALUE : null);

        String message = warning.warning();

        assertNotNull(message);
        assertTrue(message.contains("THINKLAB_PO_POLICY_ID") && message.contains("band/initiate"));
        assertFalse(message.contains(VALUE));
        warning.onApplicationEvent(mock(StartupEvent.class));
    }

    @Test
    @DisplayName("an unset or blank variable says nothing")
    void silentWhenUnset() {
        var unset = new LegacyPolicyVariableWarning(name -> null);
        var blank = new LegacyPolicyVariableWarning(name -> " ");

        assertNull(unset.warning());
        assertNull(blank.warning());
        unset.onApplicationEvent(mock(StartupEvent.class));
        blank.onApplicationEvent(mock(StartupEvent.class));
    }

    @Test
    @DisplayName("the default reads the real environment")
    void defaultReadsTheEnvironment() {
        var warning = new LegacyPolicyVariableWarning();
        String actual = System.getenv(LegacyPolicyVariableWarning.VARIABLE);

        assertTrue((actual == null || actual.isBlank()) == (warning.warning() == null));
    }
}
