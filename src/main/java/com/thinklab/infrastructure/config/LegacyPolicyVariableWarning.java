package com.thinklab.infrastructure.config;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.UnaryOperator;

/**
 * Says so at startup when the old {@code THINKLAB_PO_POLICY_ID} variable is still set (ADR-036). It is ignored now: the approval policy of an order is that of its
 * approval band, so an operator who kept the variable would otherwise believe it still decides, and learn otherwise from a 502 on the first submission.
 */
@Singleton
public class LegacyPolicyVariableWarning implements ApplicationEventListener<StartupEvent> {

    static final String VARIABLE = "THINKLAB_PO_POLICY_ID";
    private static final Logger log = LoggerFactory.getLogger(LegacyPolicyVariableWarning.class);

    private final UnaryOperator<String> environment;

    @Inject
    public LegacyPolicyVariableWarning() {
        this(System::getenv);
    }

    LegacyPolicyVariableWarning(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /** The warning to log, or null when the variable is not set (a blank value counts as not set). The value itself is never part of it. */
    String warning() {
        String value = environment.apply(VARIABLE);
        if (value == null || value.isBlank()) {
            return null;
        }
        return VARIABLE + " is set but no longer used: the approval policy of a purchase order is that of its approval band (ADR-036). "
                + "Make a band at 0 for each currency with POST /it-procurement/v1/band/initiate, then remove the variable.";
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        String warning = warning();
        if (warning != null) {
            log.warn("[CONFIG] {}", warning);
        }
    }
}
