package com.kiano.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main class of the kiano-worker process (booted from the same jar as the
 * api via -Dloader.main). The worker profile guard keeps the api's component
 * scan from loading this configuration class; here it is the primary source
 * and only scans the worker package.
 */
@SpringBootApplication(scanBasePackages = "com.kiano.worker")
@Profile("worker")
@EnableScheduling
public class KianoWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KianoWorkerApplication.class, args);
    }
}
