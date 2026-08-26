package org.example.simple;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class LogbackTest {

    private static final Logger log = LoggerFactory.getLogger(LogbackTest.class);

    @Test
    void testLog() {
        log.debug("This is a debug message");
        log.info("This is an info message");
        log.warn("This is a warn message");
        log.error("This is an error message");
    }

}
