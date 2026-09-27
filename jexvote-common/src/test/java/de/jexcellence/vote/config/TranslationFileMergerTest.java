package de.jexcellence.vote.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationFileMergerTest {

    @Test
    void quotesBooleanLikeKeysOnly() {
        String yaml = "status:\n  value:\n    on: 'On'\n    off: 'Off'\n    online: 'Online'\n    note: 'on: stays'\n";

        String fixed = TranslationFileMerger.quoteBooleanKeys(yaml);

        assertEquals("status:\n  value:\n    'on': 'On'\n    'off': 'Off'\n    online: 'Online'\n    note: 'on: stays'\n",
                fixed);
    }

    @Test
    void leavesCleanFilesUnchanged() {
        String yaml = "vote:\n  feature-on: 'On'\n  'off': 'Off'\n";

        assertEquals(yaml, TranslationFileMerger.quoteBooleanKeys(yaml));
    }
}
