package io.github.oyzh1994.xserver.test;

import io.github.oyzh1994.xserver.Main;

/**
 *
 * @author oyzh
 * @since 2026-09-21
 */
public class XServerTest {

    static void main() {
        String[] args = new String[]{"--display", "10", "--geometry", "1300x800", "--log-requests"};
        Main.main(args);
    }
}
