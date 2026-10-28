package hudson.plugins.ec2.ssh.verifiers;

import static hudson.plugins.ec2.ssh.verifiers.SshHostKeyVerificationStrategy.KEYS_BEGIN;
import static hudson.plugins.ec2.ssh.verifiers.SshHostKeyVerificationStrategy.KEYS_END;
import static hudson.plugins.ec2.ssh.verifiers.SshHostKeyVerificationStrategy.findKeyLine;
import static hudson.plugins.ec2.ssh.verifiers.SshHostKeyVerificationStrategy.stripControlSequences;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConsoleKeyParsingTest {

    // Fake keys that decode as strict, canonical base64 and use every character of the base64 alphabet, padding
    // included. The base64 alphabet is A-Z, a-z, 0-9, '+' and '/', with '=' as padding (RFC 4648 section 4), and
    // key type names are printable US-ASCII (RFC 4251 section 6). ESC (0x1B) and the other C0 control bytes are in
    // neither, so a valid key line cannot contain an escape sequence and stripping them cannot alter a valid key.
    private static final String FAKE =
            "THIS+IS/A0FAKE1HOST2KEY3NOT4A5REAL6ONE7abcdefghijklm8nopqrstuvwxyz9BCDGJMPQUVWXZ";

    // Every type that KeyHelper.getSshAlgorithm can report for a host key, in the order cloud-init prints them.
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("ecdsa-sha2-nistp256", "ECDSA256+" + FAKE + "w==");
        KEYS.put("ecdsa-sha2-nistp384", "ECDSA384+" + FAKE + "w==");
        KEYS.put("ecdsa-sha2-nistp521", "ECDSA521+" + FAKE + "w==");
        KEYS.put("ssh-ed25519", "ED25519+" + FAKE);
        KEYS.put("ssh-rsa", "RSA+" + FAKE);
        KEYS.put("ssh-dss", "DSS+" + FAKE);
    }

    // What serial-getty@ttyS0 writes when it resets the terminal on Ubuntu 26.04
    private static final String GETTY_RESET =
            "\u001B[!p\u001B]104\u001B\\\u001B[0m\u001B[?7h\u001B[1G\u001B[0J\u001B[6n\u001B[32766;32766H\u001B[6n";
    // systemd's OSC 3008 context announcement, ended by ST
    private static final String SYSTEMD_OSC = "\u001B]3008;start=0f1e;type=service;unit=cloud-final.service\u001B\\";

    private static String keyLine(String algorithm) {
        return algorithm + " " + KEYS.get(algorithm) + " root@host";
    }

    private static String allKeyLines(UnaryOperator<String> perLine) {
        return KEYS.keySet().stream()
                .map(algorithm -> "<14>cloud-init: " + perLine.apply(keyLine(algorithm)))
                .collect(Collectors.joining("\n"));
    }

    private static String keysBlock(String keyLines) {
        return "<14>cloud-init: " + KEYS_BEGIN + "\n" + keyLines + "\n<14>cloud-init: " + KEYS_END + "\n";
    }

    private static String parse(String console, String algorithm) {
        return findKeyLine(stripControlSequences(console), algorithm);
    }

    static Stream<Arguments> consoles() {
        Map<String, UnaryOperator<String>> variants = new LinkedHashMap<>();
        variants.put("block", line -> "boot\n" + keysBlock(allKeyLines(l -> l)) + "login:");
        variants.put(
                "getty reset before block",
                line -> "<14>cloud-init: ###" + GETTY_RESET + "[ 15.0] cloud-init[530]: Cloud-init v.\n"
                        + keysBlock(allKeyLines(l -> l)));
        variants.put(
                "CSI and SGR inside each key",
                line -> keysBlock(allKeyLines(l -> "\u001B[1;32m" + l.substring(0, 30) + "\u001B[6n"
                        + l.substring(30, 50) + "\u001B[32766;32766H" + l.substring(50) + "\u001B[0m")));
        variants.put(
                "OSC ended by ST and BEL inside each key",
                line -> keysBlock(allKeyLines(l -> l.substring(0, 25) + "\u001B]104\u001B\\" + l.substring(25, 60)
                        + "\u001B]0;title\u0007" + l.substring(60))));
        variants.put(
                "systemd OSC between key lines", line -> keysBlock(allKeyLines(l -> SYSTEMD_OSC + l + SYSTEMD_OSC)));
        variants.put(
                "unterminated OSC before block",
                line -> "<14>cloud-init: ###\u001B]104\n" + keysBlock(allKeyLines(l -> l)));
        variants.put(
                "bare C0 bytes and CRLF",
                line -> keysBlock(allKeyLines(l -> "\u0007" + l.substring(0, 40) + "\u0000\u0008" + l.substring(40)))
                        .replace("\n", "\r\n"));
        variants.put(
                "algorithm mentioned before block",
                line -> "sshd: rejected " + line.replace(KEYS.get(line.split(" ")[0]), "NOT+THE+KEY") + "\n"
                        + keysBlock(allKeyLines(l -> l)));
        variants.put(
                "block without END marker",
                line -> "<14>cloud-init: " + KEYS_BEGIN + "\n" + allKeyLines(l -> l) + "\n");
        variants.put("no block", line -> "A text before the key\n" + line + "\n a bit more text");
        variants.put("no block, no trailing newline", line -> "A text before the key\n" + line);

        return KEYS.keySet().stream()
                .flatMap(algorithm -> variants.entrySet().stream()
                        .map(variant -> Arguments.of(
                                algorithm, variant.getKey(), variant.getValue().apply(keyLine(algorithm)))));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("consoles")
    void findsKey(String algorithm, String variant, String console) {
        String line = parse(console, algorithm);
        assertThat(line, is(keyLine(algorithm)));
        // The same split and decode as getKeyFromLine
        assertThat(
                Base64.getDecoder().decode(line.split(" ")[1]),
                is(Base64.getDecoder().decode(KEYS.get(algorithm))));
    }

    static Stream<String> algorithms() {
        return KEYS.keySet().stream();
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void blockWithoutKeyForAlgorithm(String algorithm) {
        String others = KEYS.keySet().stream()
                .filter(other -> !other.equals(algorithm))
                .map(other -> "<14>cloud-init: " + keyLine(other))
                .collect(Collectors.joining("\n"));
        assertThat(parse(keysBlock(others), algorithm), is(nullValue()));
    }

    @Test
    void keyBlockLostToTerminalReset() {
        String console = "<14>cloud-init: -----BEGIN SSH HOST KEY FINGERPRINTS-----\n"
                + "<14>cloud-init: 256 SHA256:fp root@host (ED25519)\n"
                + "<14>cloud-init: -----END SSH HOST KEY FINGERPRINTS-----\n"
                + "<14>cloud-init: ###" + GETTY_RESET + "[ 15.0] cloud-init[530]: Cloud-init v.\n"
                + "[ 15.0] cloud-init[530]: Generating public/private rsa key pair.\n";
        assertThat(parse(console, "ssh-ed25519"), is(nullValue()));
    }

    @Test
    void beginMarkerCutByTerminalReset() {
        String console = "<14>cloud-init: -----BEGIN SSH HOS" + GETTY_RESET + "[ 15.6] cloud-init[562]\n"
                + "[ 15.6] cloud-init[562]: Generating public/private rsa key pair.\n";
        assertThat(parse(console, "ssh-ed25519"), is(nullValue()));
    }
}
