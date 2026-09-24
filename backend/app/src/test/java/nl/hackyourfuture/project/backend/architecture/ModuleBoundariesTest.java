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
 * The module boundaries, checked against the compiled classes rather than the poms.
 *
 * <p>{@code maven-enforcer} is the gate that actually stops this: it bans the dependency, and
 * without the dependency the import does not resolve. Measured rather than assumed — adding both
 * the pom entry and the import together still fails at the enforcer, before these rules run.
 *
 * <p>So what these add is <em>independence</em>. They are a statement of the boundary that does
 * not rely on the poms being right: run the same violation with {@code -Denforcer.skip=true} and
 * they fail on their own. That matters because the enforcer's rule is twenty lines of XML in a
 * parent pom, and the day someone relaxes it to unblock themselves is the day the only remaining
 * record of the rule is this file.
 *
 * <p>They live in {@code app} because it is the only module with every other on its classpath.
 * A rule about all of them cannot be checked from inside any one of them.
 *
 * <p>What is deliberately <em>not</em> here: a rule that only a module's {@code api} package may
 * be imported from outside. Today no feature module publishes anything to another — they speak
 * through interfaces in {@code shared} — so such a rule would guard packages that do not exist.
 * The Day 07 notes say when to add it.
 */
@AnalyzeClasses(
        packages = "nl.hackyourfuture.project.backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    private static final String IDENTITY = "..backend.identity..";
    private static final String JOBS = "..backend.jobs..";
    private static final String APPLICATIONS = "..backend.applications..";
    private static final String MATCHING = "..backend.matching..";
    private static final String APP = "..backend.config..";

    // Worded differently from the enforcer's message on purpose: when a build fails, which of
    // the two gates caught it is the first thing worth knowing.
    private static final String WHY =
            "this module reaches into another. A feature module may depend on shared and nothing "
                    + "else of ours - publish an interface in shared instead.";

    @ArchTest
    static final ArchRule identityKeepsToItself = noClasses()
            .that().resideInAPackage(IDENTITY)
            .should().dependOnClassesThat().resideInAnyPackage(JOBS, APPLICATIONS, MATCHING)
            .because(WHY);

    @ArchTest
    static final ArchRule jobsKeepsToItself = noClasses()
            .that().resideInAPackage(JOBS)
            .should().dependOnClassesThat().resideInAnyPackage(IDENTITY, APPLICATIONS, MATCHING)
            .because(WHY);

    /** The one Day 09 will be tested by: applications must stop reading postings. */
    @ArchTest
    static final ArchRule applicationsKeepsToItself = noClasses()
            .that().resideInAPackage(APPLICATIONS)
            .should().dependOnClassesThat().resideInAnyPackage(IDENTITY, JOBS, MATCHING)
            .because(WHY);

    @ArchTest
    static final ArchRule matchingKeepsToItself = noClasses()
            .that().resideInAPackage(MATCHING)
            .should().dependOnClassesThat().resideInAnyPackage(IDENTITY, JOBS, APPLICATIONS)
            .because(WHY);

    /**
     * {@code shared} is a leaf and has to stay one. Every module depends on it, so a dependency
     * from it to a feature module is a cycle, and the first symptom of a cycle here would be
     * two services that cannot be deployed apart.
     */
    @ArchTest
    static final ArchRule sharedDependsOnNobody = noClasses()
            .that().resideInAPackage("..backend.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(IDENTITY, JOBS, APPLICATIONS, MATCHING, APP)
            .because("shared is depended on by everything, so anything it depends on is shared too");

    /**
     * Nothing may depend on the assembly. {@code app} wires the modules together and knows about
     * all of them; a module reaching back into it would make the wiring part of the service.
     */
    @ArchTest
    static final ArchRule nobodyDependsOnTheAssembly = noClasses()
            .that().resideInAnyPackage(IDENTITY, JOBS, APPLICATIONS, MATCHING, "..backend.shared..")
            .should().dependOnClassesThat().resideInAPackage(APP)
            .because("app assembles the modules; a module that knows about the assembly is not a module");

    /**
     * A {@code RestClient} comes from the builder Spring injects, which carries the observation
     * registry. One built by {@code RestClient.builder()} or {@code RestClient.create()} makes
     * calls that no metric counts and no trace shows (Day 38): the LLM call was one, and the
     * clients Days 19, 21 and 24 add between services would be the next.
     */
    @ArchTest
    static final ArchRule restClientsComeFromSpring = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe("RestClient.builder() or RestClient.create()",
                    (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(RestClient.class)
                            && ("builder".equals(call.getName()) || "create".equals(call.getName()))))
            .because("only the RestClient.Builder Spring injects is measured and traced");
}
