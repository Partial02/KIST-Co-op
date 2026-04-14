package io.github.rladmstj.esmserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EsmServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EsmServerApplication.class, args);
    }
}
