package nl.hackyourfuture.project.backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.client.RestClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Identity's RestClient rule. The module boundary rules that used to sit beside it retired on
 * Day 43, when the shared and identity modules folded into one: with one module there is no
 * boundary left for them to check.
 */
@AnalyzeClasses(
        packages = "nl.hackyourfuture.project.backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class RestClientRuleTest {

    /**
     * A {@code RestClient} comes from the builder Spring injects, which carries the observation
     * registry. One built by {@code RestClient.builder()} or {@code RestClient.create()} makes
     * calls that no metric counts and no trace shows (Day 38): the LLM call was one, and the
     * clients Days 19, 21 and 24 add between services would be the next. The matching-service
     * and job-service modules carry the same rule in their own RestClientRuleTest classes,
     * since this one analyses the monolith's classes only.
     */
    @ArchTest
    static final ArchRule restClientsComeFromSpring = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe("RestClient.builder() or RestClient.create()",
                    (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(RestClient.class)
                            && ("builder".equals(call.getName()) || "create".equals(call.getName()))))
            .because("only the RestClient.Builder Spring injects is measured and traced");
}
