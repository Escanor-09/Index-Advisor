package advisor.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import advisor.database.PostgresManager;

@Configuration
public class AdvisorConfig {

    // destroyMethod is explicit rather than relying on Spring's default inferred-close
    // detection — PostgresManager now owns a HikariCP pool (real OS connections/threads)
    // that should shut down cleanly when the application context does.
    @Bean(destroyMethod = "close")
    public PostgresManager postgresManager(
        @Value("${db.host}") String host,
        @Value("${db.port}") String port,
        @Value("${db.name}") String dbName,
        @Value("${db.user}") String user,
        @Value("${db.password}") String password
    ) {
        // Mirrors Main.java's CLI fallback: default to the current OS user when DB_USER isn't set.
        String resolvedUser = (user == null || user.isBlank()) ? System.getProperty("user.name") : user;
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

        return new PostgresManager(url, resolvedUser, password);
    }
}
