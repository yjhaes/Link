package com.example.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class LinkApplication {

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(LinkApplication.class, args);
    }
}
