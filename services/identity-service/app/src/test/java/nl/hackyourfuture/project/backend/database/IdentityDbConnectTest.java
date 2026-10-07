package nl.hackyourfuture.project.backend.database;

import nl.hackyourfuture.project.backend.support.PostgresContainer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three copies of the role-setup code must grant CONNECT on identity_db to the same roles.
 * Each of the three drifted to 3, 4 and 6 roles (H28.3); this test pins them all to
 * {@code identity_user} alone, and the CI filter runs it whenever they change (Day 42).
 */
class IdentityDbConnectTest {

    @Test
    void allThreeRoleSetupCopiesGrantConnectToIdentityUserAlone() throws IOException {
        List<String> expected = List.of("identity_user");

        List<String> fromShell = rolesFromShellScript();
        assertThat(fromShell).as("scripts/db-init/10-module-roles.sh").isEqualTo(expected);

        List<String> fromPython = rolesFromPythonScript();
        assertThat(fromPython).as("scripts/db-setup.py").isEqualTo(expected);

        List<String> fromHarness = PostgresContainer.IDENTITY_DB_CONNECT;
        assertThat(fromHarness).as("PostgresContainer.IDENTITY_DB_CONNECT").isEqualTo(expected);
    }

    @Test
    void shellScriptHasExactlyOneGrantConnectLine() throws IOException {
        String content = Files.readString(Path.of("../../../scripts/db-init/10-module-roles.sh"));
        long count = content.lines()
                .filter(line -> line.contains("GRANT CONNECT ON DATABASE") && line.contains("identity_db"))
                .count();
        assertThat(count).as("scripts/db-init/10-module-roles.sh").isEqualTo(1);
    }

    private List<String> rolesFromShellScript() throws IOException {
        String content = Files.readString(Path.of("../../../scripts/db-init/10-module-roles.sh"));
        Pattern pattern = Pattern.compile(
                "GRANT CONNECT ON DATABASE :\"identity_db\" TO ([^;]+);", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find()).as("scripts/db-init/10-module-roles.sh contains GRANT CONNECT").isTrue();
        return List.of(matcher.group(1).split(",\\s*"));
    }

    private List<String> rolesFromPythonScript() throws IOException {
        String content = Files.readString(Path.of("../../../scripts/db-setup.py"));
        Pattern pattern = Pattern.compile(
                "IDENTITY_DB_ROLES = \\(([^)]*)\\)");
        Matcher matcher = pattern.matcher(content);
        assertThat(matcher.find()).as("scripts/db-setup.py contains IDENTITY_DB_ROLES").isTrue();
        String tuple = matcher.group(1).replaceAll("[\"']", "").trim();
        if (tuple.isEmpty()) {
            return List.of();
        }
        return List.of(tuple.split(",\\s*"));
    }
}
