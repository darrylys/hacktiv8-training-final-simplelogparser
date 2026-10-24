package id.project.logs.simplelogparser;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import id.project.logs.simplelogparser.io.LogFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Layering and read-only rules (TRD 9.6). */
class ArchitectureTest {

    private static final String ROOT = "id.project.logs.simplelogparser";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
    }

    @Test
    void coreDoesNotDependOnFrontEndsOrUiLibraries() {
        noClasses().that().resideInAnyPackage(ROOT + ".io..", ROOT + ".model..", ROOT + ".format..", ROOT + ".query..",
                        ROOT + ".scan..", ROOT + ".settings..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx..", "org.fxmisc..", "picocli..",
                        ROOT + ".cli..", ROOT + ".gui..")
                .check(classes);
    }

    @Test
    void cliAndGuiAreIndependent() {
        noClasses().that().resideInAPackage(ROOT + ".cli..").should().dependOnClassesThat().resideInAPackage(ROOT + ".gui..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage(ROOT + ".gui..").should().dependOnClassesThat().resideInAPackage(ROOT + ".cli..")
                .allowEmptyShould(true).check(classes);
    }

    @Test
    void onlySettingsUsesFileWriteApis() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".settings..")
                .should().dependOnClassesThat().areAssignableTo(FileOutputStream.class)
                .orShould().dependOnClassesThat().areAssignableTo(FileWriter.class)
                .orShould().dependOnClassesThat().areAssignableTo(RandomAccessFile.class)
                .orShould().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.base.DescribedPredicate.describe("a Files write API", t ->
                                t.getOwner().isEquivalentTo(Files.class) && (t.getName().startsWith("write")
                                        || t.getName().startsWith("newOutputStream")
                                        || t.getName().startsWith("newBufferedWriter")
                                        || t.getName().startsWith("createTemp")
                                        || t.getName().equals("createFile")
                                        || t.getName().equals("delete")
                                        || t.getName().equals("deleteIfExists")
                                        || t.getName().equals("move")
                                        || t.getName().equals("copy")))))
                .check(classes);
    }

    @Test
    void onlyLogFileOpensFileChannels() {
        noClasses().that().doNotBelongToAnyOf(LogFile.class)
                .should().callMethod(FileChannel.class, "open", java.nio.file.Path.class, java.nio.file.OpenOption[].class)
                .orShould().callMethod(FileChannel.class, "open", java.nio.file.Path.class, java.util.Set.class,
                        java.nio.file.attribute.FileAttribute[].class)
                .check(classes);
    }

    @Test
    void onlyMainCallsSystemExit() {
        noClasses().that().doNotBelongToAnyOf(Main.class)
                .should().callMethod(System.class, "exit", int.class)
                .check(classes);
    }
}
