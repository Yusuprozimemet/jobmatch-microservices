package nl.hackyourfuture.project.jobservice;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.client.RestClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Day 38's rule, {@code ModuleBoundariesTest.restClientsComeFromSpring}, for this module: that one
 * analyses the monolith's classes only, so job-service's own client ({@code SavedJobCountsClient},
 * Day 19) has never been checked by it. The package covers both of this module's roots,
 * {@code jobservice} and the {@code backend} packages it kept from the monolith.
 */
@AnalyzeClasses(packages = "nl.hackyourfuture.project", importOptions = ImportOption.DoNotIncludeTests.class)
class RestClientRuleTest {

    @ArchTest
    static final ArchRule restClientsComeFromSpring = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe("RestClient.builder() or RestClient.create()",
                    (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(RestClient.class)
                            && ("builder".equals(call.getName()) || "create".equals(call.getName()))))
            .because("only the RestClient.Builder Spring injects is measured and traced");
}
