package com.example.app.archunit;

import com.example.app.common.security.AllowedAction;
import com.example.app.support.AllowedActionContract;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.ReferencedClassObject;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedActionContractArchTest {

    private static final String BASE_PACKAGE = "com.example.app";

    @Test
    @DisplayName("Every flag record main code builds has its AllowedActionContract (AUTHZ-07); a copy with none passes")
    void everyFlagRecordHasItsContract() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
        JavaClasses tests = new ClassFileImporter().withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
        Set<String> contracted = contractedRecords(tests);

        Set<String> uncontracted = main.stream()
                .filter(AllowedActionContractArchTest::isFlagRecordBuiltByMainCode)
                .map(JavaClass::getName)
                .filter(record -> !contracted.contains(record))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(uncontracted).as("flag records no test passes to AllowedActionContract.of").isEmpty();
    }

    /**
     * A record no main class builds is a shared shape this copy doesn't use (a copy without the example still has
     * {@code SelectableDropdownItem}): no endpoint answers it, so there is no permission to prove.
     */
    private static boolean isFlagRecordBuiltByMainCode(JavaClass type) {
        boolean flagRecord = type.getRawSuperclass().map(superclass -> superclass.isEquivalentTo(Record.class)).orElse(false)
                && type.getFields().stream().anyMatch(field -> field.getRawType().isEquivalentTo(AllowedAction.class));
        return flagRecord && type.getConstructorCallsToSelf().stream()
                .anyMatch(call -> !call.getOriginOwner().equals(type));
    }

    /**
     * The class literal on the line of an {@code AllowedActionContract.of} call is its argument: the bytecode keeps
     * no link between the two, and the line ties them closer than "the test mentions both". Splitting
     * {@code of(} and {@code X.class} over two lines fails this test, never passes it wrongly.
     */
    private static Set<String> contractedRecords(JavaClasses tests) {
        return tests.stream()
                .flatMap(test -> test.getCodeUnits().stream())
                .flatMap(AllowedActionContractArchTest::recordsPassedToContract)
                .collect(Collectors.toSet());
    }

    private static Stream<String> recordsPassedToContract(JavaCodeUnit codeUnit) {
        Set<Integer> contractLines = codeUnit.getMethodCallsFromSelf().stream()
                .filter(AllowedActionContractArchTest::isContractOf)
                .map(JavaMethodCall::getLineNumber)
                .collect(Collectors.toSet());
        return codeUnit.getReferencedClassObjects().stream()
                .filter(literal -> contractLines.contains(literal.getLineNumber()))
                .map(ReferencedClassObject::getRawType)
                .filter(literal -> !literal.isEquivalentTo(AllowedActionContract.class))
                .map(JavaClass::getName);
    }

    private static boolean isContractOf(JavaMethodCall call) {
        return call.getTargetOwner().isEquivalentTo(AllowedActionContract.class) && call.getName().equals("of");
    }
}
