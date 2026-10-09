package com.kiano.worker;

import com.kiano.worker.ffmpeg.FfmpegExecutor;
import com.kiano.worker.ffmpeg.FfmpegProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
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

    private static final Logger LOG = LoggerFactory.getLogger(KianoWorkerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(KianoWorkerApplication.class, args);
    }

    /**
     * Probes ffmpeg once at startup so a missing binary is obvious in the log.
     * The loop reports the FFMPEG capability only while this probe (cached for
     * about 10 seconds) keeps succeeding.
     */
    @Bean
    ApplicationRunner ffmpegStartupProbe(FfmpegExecutor executor, FfmpegProperties properties) {
        return args -> {
            if (executor.available()) {
                LOG.info("ffmpeg available at {} - video composition enabled",
                        properties.getFfmpegPath());
            } else {
                LOG.warn("ffmpeg not available at {} - VIDEO_COMPOSE jobs stay unleased on this worker",
                        properties.getFfmpegPath());
            }
        };
    }
}
