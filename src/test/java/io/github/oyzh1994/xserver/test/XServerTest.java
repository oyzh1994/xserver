package io.github.oyzh1994.xserver.test;

import io.github.oyzh1994.xserver.Main;
import org.junit.jupiter.api.Test;

/**
 *
 * @author oyzh
 * @since 2026-09-21
 */
public class XServerTest {

    @Test
    public void test1() {
        String[] args = new String[]{"--display:1"};
        Main.main(args);
    }

    static void main() {
        String[] args = new String[]{"--display:1"};
        Main.main(args);
    }
}
