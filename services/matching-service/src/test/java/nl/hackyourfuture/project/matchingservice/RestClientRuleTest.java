package nl.hackyourfuture.project.matchingservice;

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
 * analyses the monolith's classes only, so matching's clients leave its reach when they move here.
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
