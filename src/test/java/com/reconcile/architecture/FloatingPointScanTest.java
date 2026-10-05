package com.reconcile.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The definition of done's first box, made true rather than believed: <b>no floating-point
 * representation anywhere in the main sources</b>, checked by reading all of them.
 *
 * <p>This exists because {@code ClassifierArithmeticTest} checks four files. Four files is not the
 * claim. The claim is that the system moves money in {@code long} minor units everywhere, and a
 * hand-maintained list of the places that are true decays the first time somebody adds a comparison
 * in a package nobody remembered to extend it to — which is exactly the kind of convenience change
 * that is otherwise entirely reasonable and entirely unreviewed.
 *
 * <p>Why a source scan and not a lint rule: the property is about the <i>source</i>, and the
 * strongest statement available without adding a static-analysis dependency is to read the files.
 * A reader can verify the rule by reading the rule. {@code BigDecimal} is allowed in exactly one
 * file, for a stated reason — see {@link #BIG_DECIMAL_EXEMPT}.
 *
 * <p>Two failure modes are guarded against explicitly, because a source-scanning test fails
 * <i>silently</i> in both directions: a walk that finds no files would pass while checking nothing,
 * and a scanner whose patterns stopped matching would pass while checking everything. Hence the
 * file-count floor and {@link #theDetectorWouldActuallyFlagFloatingPoint()}.
 *
 * <p><b>F-10: type names alone were not the claim.</b> The scan looked for the words
 * {@code double}/{@code float}, and the simulator computed its fee with
 * {@code grossAmountMinor * 0.03d} — a binary floating-point literal in a monetary path, in the
 * one file nobody looks at, producing the right answer for the amounts it happened to be tested
 * with. The scanner could not see it, so "no floating point anywhere in main sources" was false
 * while its own test passed. {@link #FLOATING_POINT_LITERALS} closes that: {@code 0.03d} has no
 * floating-point <i>type</i>, only a floating-point <i>value</i>.
 */
class FloatingPointScanTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /**
     * The floor for "the walk found the sources".
     *
     * <p>Set below the real count rather than equal to it so that adding or removing a class does
     * not require editing this test to keep it honest. There are 68 today; anything under 40 means
     * the path is wrong, the build is running from an unexpected directory, or the tree moved — all
     * of which would make every assertion below vacuously true.
     */
    private static final int MINIMUM_FILES_SCANNED = 40;

    /**
     * Floating point as types, primitive and boxed.
     *
     * <p>Boxed types are included deliberately: {@code Double} on an accounting path is the same
     * defect as {@code double}, and it is the one that survives a skim because it reads as an
     * ordinary object.
     */
    private static final Pattern FLOATING_POINT =
            Pattern.compile("\\b(?:double|float|Double|Float)\\b");

    /**
     * Floating point as a <b>literal</b>, which is the half the type scan could not see.
     *
     * <p>Three forms, because Java has three and each has been used to hide a fee:
     *
     * <ul>
     *   <li>{@code 0.03} — a decimal point is sufficient on its own; {@code int x = 1.0} is a
     *       {@code double} without the word appearing anywhere.</li>
     *   <li>{@code 3.0d}, {@code 1f} — the explicit suffix, including the lowercase {@code f} that
     *       a reader skims straight past.</li>
     *   <li>{@code 1e9} — an exponent is a fractional value written without a decimal point.</li>
     * </ul>
     *
     * <p><b>Why the long suffix is not matched.</b> {@code 0L} and {@code 1_000_000_000_000L} are
     * integer literals, and this codebase uses them on almost every line — matching {@code [lL]} as
     * if it were a float would produce hundreds of findings and train everyone to ignore the scan.
     *
     * <p><b>Why hex is safe.</b> {@code 0x1F} cannot match: {@code x} and {@code 1} are both word
     * characters, so there is no {@code \b} before the {@code 1}, and the whole token is one word.
     * The {@code \b} anchors are load-bearing here, not decoration.
     */
    private static final Pattern FLOATING_POINT_LITERALS = Pattern.compile(
            "\\b\\d[\\d_]*\\.\\d[\\d_]*\\b"          // 0.03, 1.0
                    + "|\\b\\d[\\d_]*[dDfF]\\b"            // 0.03d, 3f
                    + "|\\b\\d[\\d_]*[eE][+-]?\\d+\\b");   // 1e9, 2.5E-3

    /**
     * Where {@code BigDecimal} is permitted, and why.
     *
     * <p>{@code Money} uses it in exactly two places, as an integer-rounding helper when computing
     * a fee from basis points and when rendering minor units for humans. Both are conversions at
     * the edges of the system; the amount itself is a {@code long} from there to the database. The
     * exemption is a named file rather than a regex or a threshold, because a rule with a shape
     * invites negotiation and a rule with a name can be argued with in a code review.
     *
     * <p>It is also deliberately not {@code MoneyDto}: a DTO that renders money for a client is
     * exactly where a decimal would take hold, and nothing there needs one.
     */
    private static final Set<String> BIG_DECIMAL_EXEMPT = Set.of(
            "src/main/java/com/reconcile/shared/Money.java");

    @Test
    @DisplayName("U-MONEY-06: no double, float, Double or Float appears in any main source")
    void noFloatingPointAnywhereInTheMainSources() throws IOException {
        List<Path> sources = mainSources();

        assertThat(sources.size())
                .as("the walk must actually find the sources; %d files is below the floor of %d, "
                        + "so every assertion below would be vacuously true",
                        sources.size(), MINIMUM_FILES_SCANNED)
                .isGreaterThanOrEqualTo(MINIMUM_FILES_SCANNED);

        for (Path source : sources) {
            String code = stripNonCode(Files.readString(source, StandardCharsets.UTF_8));

            assertThat(FLOATING_POINT.matcher(code).find())
                    .as("%s uses a floating-point type. Money is carried in long minor units; a "
                            + "decimal here is how 0.1 + 0.2 stops being 0.3 without anything "
                            + "failing at the point of the mistake.",
                            source)
                    .isFalse();

            assertThat(FLOATING_POINT_LITERALS.matcher(code).find())
                    .as("%s contains a floating-point literal. This is F-10: %s used "
                            + "`grossAmountMinor * 0.03d` and the type scan could not see it, so the "
                            + "'no floating point in main sources' claim was false while its own test "
                            + "passed. Express a rate in basis points and round it with Money.",
                            source, source)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("U-MONEY-06b: BigDecimal appears only in Money, and nowhere else in main")
    void bigDecimalIsConfinedToTheOneFileThatNeedsIt() throws IOException {
        for (Path source : mainSources()) {
            String relative = source.toString().replace('\\', '/');
            String code = stripNonCode(Files.readString(source, StandardCharsets.UTF_8));

            if (!code.contains("BigDecimal")) {
                continue;
            }

            assertThat(BIG_DECIMAL_EXEMPT)
                    .as("%s uses BigDecimal. Only %s may: it rounds basis points at the edges and "
                            + "nowhere else has a reason to hold a decimal. If this is a new "
                            + "rounding conversion, move it into Money rather than widening the "
                            + "exemption.",
                            relative, BIG_DECIMAL_EXEMPT)
                    .contains(relative);
        }
    }

    @Test
    @DisplayName("U-MONEY-06c: the detector would actually flag floating point, and ignores prose about it")
    void theDetectorWouldActuallyFlagFloatingPoint() {
        String offending = "private double rate; private Float weight; var d = Double.valueOf(1);";
        String innocent = """
                /**
                 * A double-posting index excludes this type. Never a float.
                 * var rate = "float rate";
                 */
                class Rates {
                    long amountMinor() {
                        return total / 100L;   // a float would be wrong here
                    }
                }
                """;

        assertThat(FLOATING_POINT.matcher(stripNonCode(offending)).find())
                .as("a scan whose patterns no longer match reports a clean codebase forever, which "
                        + "is the worst possible failure for a test whose only job is to look")
                .isTrue();
        assertThat(FLOATING_POINT.matcher(stripNonCode(innocent)).find())
                .as("documentation about floating point is not floating point, and failing on it "
                        + "would train people to weaken the rule")
                .isFalse();
    }

    @Test
    @DisplayName("F-10: the literal detector flags every floating-point value form, and no others")
    void theLiteralDetectorIsNotVacuous() {
        // The exact expression the simulator used, which is the whole of F-10.
        String theOffender = "long fee = Math.round(grossAmountMinor * 0.03d);";
        assertThat(FLOATING_POINT.matcher(stripNonCode(theOffender)).find())
                .as("the type scan passed this line, which is why the claim was false for a year")
                .isFalse();
        assertThat(FLOATING_POINT_LITERALS.matcher(stripNonCode(theOffender)).find())
                .as("and this is the assertion that would have caught it")
                .isTrue();

        for (String shape : new String[] {
            "long a = gross * 0.03;",     // decimal point
            "long b = gross * 3.0d;",    // explicit double suffix
            "long c = gross * 3f;",      // lowercase float suffix
            "long d = gross * 3.0F;",    // uppercase float suffix
            "long e = (long) (1e9 * x);" // exponent form
        }) {
            assertThat(FLOATING_POINT_LITERALS.matcher(stripNonCode(shape)).find())
                    .as("%s must be flagged", shape)
                    .isTrue();
        }

        // The control half. A detector that flags every integer would be as useless as one that
        // flags nothing, and would be silenced the first time somebody made it quiet.
        for (String shape : new String[] {
            "long total = 0L;",
            "long cap = 1_000_000_000_000L;",
            "long t = System.currentTimeMillis();",
            "int mask = 0x1F;",
            "int c = 'a';",
            "long pct = gross * 300L / 10_000L;",
            "long tenMinutes = Duration.ofMinutes(10).toMillis();",
            "var host = java.net.InetAddress.getLoopbackAddress();",
            "var locale = Locale.ROOT;",
        }) {
            assertThat(FLOATING_POINT_LITERALS.matcher(stripNonCode(shape)).find())
                    .as("%s is integer arithmetic and must not be flagged", shape)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("F-10: the literal detector ignores literals that only appear in strings")
    void theLiteralDetectorIgnoresStringsAndComments() {
        String documentation = """
                /**
                 * The provider's fee was 0.03 of gross, expressed as 0.03d before the fix.
                 * A ratio like 1.5 or 3.0d is not permitted in main sources.
                 */
                class Rates {
                    long fee(long gross) {
                        return gross * 300L / 10_000L;   // was gross * 0.03d
                    }
                }
                """;

        assertThat(FLOATING_POINT_LITERALS.matcher(stripNonCode(documentation)).find())
                .as("this file's javadoc discusses the defect it fixed; failing on it would make "
                        + "the rule impossible to write down, and would push the explanation into a "
                        + "commit message instead of the source")
                .isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> tree = Files.walk(MAIN)) {
            return tree.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (UncheckedIOException e) {
            throw new IOException("could not walk " + MAIN.toAbsolutePath(), e);
        }
    }

    /**
     * Reduces a source file to the parts that are actually code.
     *
     * <p>Comments, string literals and text blocks are removed, because prose about floating point
     * is documentation and a message that says {@code "float rate"} is a string, not an
     * arithmetic type. Escaped quotes are handled so that a string containing an escaped quote
     * cannot swallow the rest of the file — which would turn the scan into a no-op for that file
     * without any visible sign.
     */
    private static String stripNonCode(String source) {
        return source
                // Block comments and javadoc first, so a "double" inside a doc cannot open a
                // string that runs to the end of the file.
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                // Text blocks before plain literals, since a text block starts with one.
                .replaceAll("(?s)\"\"\".*?\"\"\"", " ")
                // A string literal: an escaped character, or anything that is neither a quote nor
                // a backslash.
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}
