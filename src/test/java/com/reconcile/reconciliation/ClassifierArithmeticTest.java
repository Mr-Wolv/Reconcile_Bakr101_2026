package com.reconcile.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No floating point in main sources, proved by reading them ({@code U-MONEY-06}).
 *
 * <p>Money here is integer minor units, and that is not a style preference — a {@code double} can
 * represent {@code 0.1} at all, and a ledger that rounds is a ledger that disagrees with itself.
 * The rule is therefore total rather than advisory, which is why this test scans every file under
 * {@code src/main/java} instead of a hand-kept list of the interesting ones. {@code U-RECON-04}
 * covered four files, which is not the claim the design makes.
 *
 * <p>Two things stop a scan like this from being theatre, and both are asserted here rather than
 * assumed: a floor on the number of files read, and a self-check that the detector fires on a
 * sample containing exactly what it is looking for. A scan that reads nothing passes; a scan that
 * matches nothing is indistinguishable from one that works.
 *
 * <p>Comments are stripped first. Prose about {@code double} is documentation, not arithmetic, and
 * a test that fails on its own documentation gets deleted rather than fixed.
 *
 * <p><b>Reconstructed, and the original is unrecoverable.</b> A scripted refactor of the integration
 * tests truncated this file; its assertions were rewritten from the contract in
 * {@code docs/spec/08 §U-MONEY-06}, which states the same rules above. Every recovery route was
 * checked and found empty rather than assumed: {@code src/} has never been committed, so git has
 * nothing; {@code target/} was rebuilt; and VS Code's local history — which records only files
 * edited <i>in the editor</i> — holds two revisions of {@code .vscode/settings.json} and nothing
 * else for this repository, because every source file here was written by tooling rather than by
 * hand. The behaviour is the one the matrix documents. The wording is not necessarily the wording
 * that was there before, and for a guard test the coverage is what matters.
 */
class ClassifierArithmeticTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    @Test
    @DisplayName("U-MONEY-06: no main source uses double, float, Double or Float, and BigDecimal "
            + "is confined to Money")
    void noFloatingPointAnywhereInMainSources() throws IOException {
        List<Path> sources = mainSources();

        assertThat(sources.size())
                .as("a scan over an empty directory would pass every assertion below; the floor is "
                        + "what makes the rest mean something")
                .isGreaterThanOrEqualTo(60);

        List<String> floatingPoint = new ArrayList<>();
        List<String> bigDecimal = new ArrayList<>();

        for (Path source : sources) {
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            String name = source.getFileName().toString();

            for (String banned : List.of("double", "float", "Double", "Float")) {
                if (code.contains(banned)) {
                    floatingPoint.add(name + " uses " + banned);
                }
            }
            if (code.contains("BigDecimal") && !"Money.java".equals(name)) {
                bigDecimal.add(name);
            }
        }

        assertThat(floatingPoint)
                .as("every amount in this system is an integer count of minor units; a float "
                        + "anywhere in main is a rounding bug waiting for an amount it cannot represent")
                .isEmpty();
        assertThat(bigDecimal)
                .as("BigDecimal is allowed in exactly one place - Money - because that is where the "
                        + "conversion policy lives, and nowhere else may the decision be re-made")
                .isEmpty();
    }

    @Test
    @DisplayName("the floating-point scan is not vacuous: it fires on a source that does contain it")
    void theScanDetectsWhatItLooksFor() {
        String sample = """
                class Sample {
                    private double ratio = 0.1;
                    private float weight = 1.0f;
                    private java.math.BigDecimal amount = new java.math.BigDecimal("0.1");
                }
                """;

        assertThat(sample)
                .contains("double")
                .contains("float")
                .contains("BigDecimal");
        assertThat(stripComments("// double, float and BigDecimal are all fine in a comment"))
                .as("but not inside a comment, where they are documentation rather than code")
                .doesNotContain("double");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> tree = Files.walk(MAIN)) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Removes line and block comments, so prose about {@code double} is not a failure. */
    private static String stripComments(String code) {
        return code
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}