package com.thinklab.domain.model;

import com.thinklab.domain.model.Promise.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PromiseTest {

    @Test
    @DisplayName("a promise is kept, not judged, or broken with a fixed code; only a broken one has a code")
    void factories() {
        assertEquals(Verdict.KEPT, Promise.kept().verdict());
        assertNull(Promise.kept().code());
        assertEquals(Verdict.NOT_JUDGED, Promise.notJudged().verdict());
        assertNull(Promise.notJudged().code());
        assertEquals(Verdict.BROKEN, Promise.broken("X").verdict());
        assertEquals("X", Promise.broken("X").code());
    }
}
