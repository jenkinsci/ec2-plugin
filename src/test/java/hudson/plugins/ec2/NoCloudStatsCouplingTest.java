package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard proving the always-loaded ec2-plugin core is free of any {@code cloud-stats} reference, so the
 * plugin is load-safe without the {@code cloud-stats} plugin <em>by construction</em> (JENKINS-55084).
 *
 * <p>All {@code cloud-stats}-touching code lives behind {@code @OptionalExtension(requirePlugins = "cloud-stats")} in
 * the {@code hudson.plugins.ec2.cloudstats} package. The core classes in {@link #CORE_CLASSES} -- and every nested or
 * anonymous type they compile to -- must never mention any {@code org.jenkinsci.plugins.cloudstats} type; otherwise the
 * plugin would fail class loading with {@code NoClassDefFoundError} on a controller that does not have
 * {@code cloud-stats} installed. This test scans the compiled constant pool of each such class and fails if the
 * forbidden package prefix appears, so the optional-dependency guarantee cannot silently regress.
 *
 * <p>Scanning the constant pool (rather than booting Jenkins with the plugin excluded) is a deliberate choice: the
 * {@code JenkinsRule} harness cannot readily exclude an on-classpath plugin, whereas a bytecode scan is a reliable,
 * fast, standard technique for enforcing a no-dependency boundary.
 */
class NoCloudStatsCouplingTest {

    /** Internal-name prefix (JVM {@code /}-separated) shared by every {@code cloud-stats} type. */
    private static final String FORBIDDEN = "org/jenkinsci/plugins/cloudstats";

    /**
     * The always-loaded core classes that must stay {@code cloud-stats}-reference-free. Held as {@link Class} literals
     * rather than names so a rename or move breaks compilation here instead of silently un-scoping the scan. Their
     * nested and anonymous types (compiled to {@code Outer$Inner.class} / {@code Outer$1.class}) are scanned alongside
     * each one.
     */
    private static final List<Class<?>> CORE_CLASSES = List.of(
            EC2AbstractSlave.class,
            EC2OndemandSlave.class,
            EC2SpotSlave.class,
            EC2Computer.class,
            EC2Cloud.class,
            EC2Step.class,
            SlaveTemplate.class,
            EC2ProvisioningTracker.class);

    @Test
    void alwaysLoadedCoreDoesNotReferenceCloudStats() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> coreClass : CORE_CLASSES) {
            List<Path> classFiles = classFilesFor(coreClass);
            // A core class scanning to nothing would let the guard pass vacuously -- fail loudly instead, so the
            // guarantee is never silently un-enforced.
            assertFalse(
                    classFiles.isEmpty(),
                    "No compiled class file found for core class " + coreClass.getName() + "; the scan would cover "
                            + "nothing.");
            for (Path classFile : classFiles) {
                if (referencesCloudStats(classFile)) {
                    offenders.add(classFile.getFileName().toString());
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "Always-loaded core classes must not reference '" + FORBIDDEN
                        + "' -- all cloud-stats coupling belongs behind @OptionalExtension in the cloudstats package. "
                        + "Offending compiled classes: " + offenders);
    }

    /** All compiled class files for {@code coreClass}: the class itself plus its nested and anonymous types. */
    private static List<Path> classFilesFor(Class<?> coreClass) throws IOException, URISyntaxException {
        String simpleName = coreClass.getSimpleName();
        URL url = coreClass.getResource(simpleName + ".class");
        assertNotNull(url, "Could not locate compiled " + coreClass.getName() + " on the classpath");
        assertEquals(
                "file",
                url.getProtocol(),
                "Expected core classes as loose .class files under target/classes, but resolved to: " + url);
        Path packageDir = Path.of(url.toURI()).getParent();
        String exact = simpleName + ".class";
        String nestedPrefix = simpleName + "$";
        try (Stream<Path> entries = Files.list(packageDir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals(exact) || (name.startsWith(nestedPrefix) && name.endsWith(".class"));
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * Parses the constant pool of a compiled class and returns {@code true} if any {@code CONSTANT_Utf8} entry contains
     * {@link #FORBIDDEN}. Every type, field and method reference in bytecode is stored in the constant pool as a
     * modified-UTF-8 string, so a constant-pool scan catches every reference a class makes to a {@code cloud-stats}
     * type without executing any code.
     */
    private static boolean referencesCloudStats(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                throw new IOException("Not a Java class file: " + classFile);
            }
            in.readUnsignedShort(); // minor_version
            in.readUnsignedShort(); // major_version
            int constantPoolCount = in.readUnsignedShort();
            // Indices run 1..constantPoolCount-1; Long/Double entries occupy two slots (JVMS 4.4.5).
            for (int i = 1; i < constantPoolCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: // CONSTANT_Utf8 -- the only entry that carries a readable name
                        if (in.readUTF().contains(FORBIDDEN)) {
                            return true;
                        }
                        break;
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        in.readUnsignedShort();
                        break;
                    case 15: // MethodHandle: u1 reference_kind + u2 reference_index
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 3: // Integer
                    case 4: // Float
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        in.readInt();
                        break;
                    case 5: // Long
                    case 6: // Double
                        in.readLong();
                        i++; // occupies the next constant pool slot too
                        break;
                    default:
                        throw new IOException(
                                "Unknown constant pool tag " + tag + " at index " + i + " in " + classFile);
                }
            }
        }
        return false;
    }
}
