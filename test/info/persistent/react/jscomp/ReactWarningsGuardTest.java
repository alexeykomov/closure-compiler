package info.persistent.react.jscomp;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import info.persistent.react.jscomp.PropTypesExtractor;
import info.persistent.react.jscomp.ReactCompilerPass;
import info.persistent.react.jscomp.ReactWarningsGuard;
import org.junit.Test;

import java.io.PrintStream;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test {@link ReactWarningsGuard}.
 */
public class ReactWarningsGuardTest {
  @Test
  public void testReactWarningsGuard() {
    Compiler compiler = new Compiler();
    CompilerOptions options = new CompilerOptions();
    options.addWarningsGuard(new ReactWarningsGuard());
    // Should not warn about non-standard JSDoc.
    List<SourceFile> inputs = ImmutableList.of(
        SourceFile.fromCode("/src/react.js", "/** @providesModule React */"));
    List<SourceFile> externs = ImmutableList.of(
        SourceFile.fromCode("externs", ""));
    Result result = compiler.compile(externs, inputs, options);
    assertTrue(result.success);
    assertEquals(result.errors.length, 0);
    assertEquals(result.warnings.length, 0);
  }

  @Test
  public void testPropsValidator() {
    Compiler compiler = new Compiler(
        new PrintStream(ByteStreams.nullOutputStream())); // Silence logging
    CompilerOptions options = new CompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS
        .setOptionsForCompilationLevel(options);
    WarningLevel.VERBOSE.setOptionsForWarningLevel(options);
    options.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT5);
    options.setWarningLevel(
        DiagnosticGroups.MISSING_PROPERTIES, CheckLevel.ERROR);
    // Report warnings as errors to make tests simpler
    options.addWarningsGuard(new StrictWarningsGuard());
    ReactCompilerPass.Options passOptions = new ReactCompilerPass.Options();
    passOptions.propTypesTypeChecking = true;
    ReactCompilerPass compilerPass = new ReactCompilerPass(
        compiler, passOptions);
    options.addCustomPass(CustomPassExecutionTime.BEFORE_CHECKS, compilerPass);
    options.addWarningsGuard(new ReactWarningsGuard(compiler, compilerPass));
    String inputJs = "var Comp = React.createClass({" +
        "propTypes: {" +
          "strProp: React.PropTypes.string" +
        "}," +
        "render: function() {return null;}" +
      "});\n" +
      "React.createElement(Comp, {strProp: 1});";
    List<SourceFile> inputs = ImmutableList.of(
        SourceFile.fromCode("/src/react.js", "/** @providesModule React */"),
        SourceFile.fromCode("/src/test.js", inputJs));
    List<SourceFile> externs = ImmutableList.of(
        SourceFile.fromCode(
          "externs",
          "/** @constructor */ function Element() {};\n" +
          "/** @constructor */ function Event() {};"));
    Result result = compiler.compile(externs, inputs, options);
    assertFalse(result.success);
    assertEquals(1, result.errors.length);
    JSError error = result.errors[0];
    assertFalse(
        error.description,
        error.description.contains("Comp$$PropsValidator"));
    assertTrue(
        error.description,
        error.description.contains("\"strProp\" was expected to be of type"));
    assertEquals(
        PropTypesExtractor.PROP_TYPES_VALIDATION_MISMATCH, error.getType());
  }
}
