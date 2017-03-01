package info.persistent.react.jscomp;

import info.persistent.react.jscomp.React;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test {@link React}.
 */
public class ReactTest {
  @Test
  public void testIsReactSourceName() {
    assertTrue(React.isReactSourceName("/src/react.js"));
    assertFalse(React.isReactSourceName("/src/notreact.js"));
    assertTrue(React.isReactSourceName("/src/react.min.js"));
    assertFalse(React.isReactSourceName("/src/react.max.js"));
    assertTrue(React.isReactSourceName("/src/react-with-addons.js"));
  }

  @Test
  public void testIsReactMinSourceName() {
    assertFalse(React.isReactMinSourceName("/src/react.js"));
    assertTrue(React.isReactSourceName("/src/react.min.js"));
    assertFalse(React.isReactSourceName("/src/reactmin.js"));
    assertTrue(React.isReactSourceName("/src/react-with-addons.min.js"));
  }
}
