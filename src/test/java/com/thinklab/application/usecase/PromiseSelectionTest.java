package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromiseSelectionTest {

    private static final Instant SINCE = Instant.parse("2026-10-19T00:00:00Z");

    private static PromiseResponse promise(String name, String verdict, Instant changedAt) {
        return new PromiseResponse(UUID.randomUUID(), name, null, verdict, "BROKEN".equals(verdict) ? "CODE" : null, "ACTIVE", changedAt);
    }

    @Test
    @DisplayName("every broken promise and every other that changed since the moment, broken first, each limited; a quiet one that is not broken is left out")
    void selects() {
        List<PromiseResponse> all = List.of(promise("quiet", "KEPT", SINCE.minusSeconds(1)), promise("recent", "KEPT", SINCE), promise("old broken", "BROKEN", SINCE.minusSeconds(999)),
                promise("not judged", "NOT_JUDGED", SINCE.plusSeconds(5)), promise("second broken", "BROKEN", SINCE.plusSeconds(1)));

        assertEquals(List.of("old broken", "second broken", "recent", "not judged"), PromiseSelection.select(all, SINCE, 10).stream().map(PromiseResponse::name).toList());
        assertEquals(List.of("old broken", "recent"), PromiseSelection.select(all, SINCE, 1).stream().map(PromiseResponse::name).toList());
    }

    @Test
    @DisplayName("the days and the limit have defaults and bounds")
    void parameters() {
        assertEquals(30, PromiseSelection.days(null));
        assertEquals(1, PromiseSelection.days(1));
        assertEquals(365, PromiseSelection.days(365));
        assertEquals(500, PromiseSelection.limit(null));
        assertEquals(1, PromiseSelection.limit(1));
        assertEquals(1000, PromiseSelection.limit(1000));
    }

    @Test
    @DisplayName("the selection is a utility class")
    void utility() throws ReflectiveOperationException {
        java.lang.reflect.Constructor<PromiseSelection> constructor = PromiseSelection.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class, constructor::newInstance);
    }
}
