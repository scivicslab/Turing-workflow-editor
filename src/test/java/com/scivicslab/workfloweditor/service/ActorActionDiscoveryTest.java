package com.scivicslab.workfloweditor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the S6→S7 ActorActionDiscovery contract:
 * pure-Java logic for building Javadoc URL parameter strings from reflection Method objects.
 *
 * discoverActions() itself requires a live IIActorRef<?> and is covered by
 * integration tests. This class targets the one pure-Java piece: the
 * parameter-type-list builder used when constructing Javadoc anchor URLs.
 *
 * Pure-Java unit tests. No Quarkus, no HTTP, no actor system.
 */
@DisplayName("S6→S7 Actor action discovery: Javadoc URL parameter string builder")
class ActorActionDiscoveryTest {

    private static Method method(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("javadocParams: parameter type list for Javadoc anchor")
    class JavadocParams {

        @Test
        @DisplayName("no-arg method produces empty string")
        void noArgs() throws Exception {
            Method m = Object.class.getMethod("toString");
            assertEquals("", WorkflowRunner.javadocParams(m));
        }

        @Test
        @DisplayName("single String param produces 'String'")
        void singleStringParam() {
            Method m = method(StringBuilder.class, "append", String.class);
            assertEquals("String", WorkflowRunner.javadocParams(m));
        }

        @Test
        @DisplayName("two params produces comma-separated simple names")
        void twoParams() {
            Method m = method(String.class, "indexOf", String.class, int.class);
            assertEquals("String,int", WorkflowRunner.javadocParams(m));
        }

        @Test
        @DisplayName("primitive int param produces 'int' not 'Integer'")
        void primitiveInt() {
            Method m = method(String.class, "charAt", int.class);
            assertEquals("int", WorkflowRunner.javadocParams(m));
        }

        @Test
        @DisplayName("result string contains no spaces")
        void noSpaces() {
            Method m = method(String.class, "indexOf", String.class, int.class);
            assertFalse(WorkflowRunner.javadocParams(m).contains(" "),
                    "Javadoc param string must not contain spaces");
        }
    }
}
