package advisor.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HTTP entry point (Milestone 14). Kept separate from Main.java, which
 * stays as the standalone CLI demo used to verify Milestones 2-13 — one
 * responsibility per entry point, same as everywhere else in this project.
 */
@SpringBootApplication
public class IndexAdvisorApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexAdvisorApplication.class, args);
    }
}
