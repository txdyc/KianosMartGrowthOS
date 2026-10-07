package com.kiano;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KianoApplication {

    public static void main(String[] args) {
        // All timestamps are stored as timestamptz (UTC); keep the JVM in UTC too.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(KianoApplication.class, args);
    }
}
