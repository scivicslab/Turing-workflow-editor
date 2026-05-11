package com.scivicslab.workfloweditor.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Forces UTF-8 encoding for stdout, stderr, and JVM default charset at startup.
 *
 * Without this, System.out.println() on platforms where the JVM default
 * encoding is not UTF-8 will silently corrupt non-ASCII characters in
 * workflow log output.
 */
@ApplicationScoped
public class EncodingConfig {

    void onStart(@Observes StartupEvent ev) {
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("stdout.encoding", "UTF-8");
        System.setProperty("stderr.encoding", "UTF-8");
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    }
}
