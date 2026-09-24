package nl.hackyourfuture.project.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The API gateway (Day 15): the one door in front of the backend. It routes by path today; the
 * token checks, header handling and rate limit come in the tracks after this one.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
