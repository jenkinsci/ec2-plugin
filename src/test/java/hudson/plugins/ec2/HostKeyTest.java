package hudson.plugins.ec2;

import static org.junit.Assert.assertEquals;

import hudson.plugins.ec2.ssh.verifiers.HostKey;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class HostKeyTest {

    public static String PUBLIC_KEY_SSH_DSS_1024 =
            "AAAAB3NzaC1kc3MAAACBAMsQrriFgun2KVgmsGd8drsplZLXyU8uU6r90aIZ+evRpxvoLCJf317Wnu5qVBCzGgEZ8iygYB0bDB/JFch+UVgtyXGH358ClJCDDgNWdOSogTl2gCF+W+8KoRSF+i3ObnEPOTa2akByP5FDzOO+mruVPl8kg8NHYcadCtJizRjhAAAAFQCV9uGT1Mchfbm6uFxEmZf09DwjSQAAAIAyyLw64QIHel17rzdyMyvepkvW4q64WYb7xCVLffaYJA8x1pxHtH4Mmmm0fGG7GFgdnCeD95524CYZR7TDhzKFGcEX607qKg0v5sXs6z8U8lGOeARq/IXQphb7YPZ9PdKUIuJImQEXriI0p5G7aGMmSYjnyEpKhUsM12xpDb2qBAAAAIBbIZPuZzBbbeesmzmGoG63w0tFc+tpPV3lNkAeYYcWpVhpSdHGFatr1lU+8LNT6OXekV2CFyF5kuuYw/B3OFkmHasURnT1+yC49OEpSzA3KOtQzqO2BZqIxDG/IEajKtSPGOWWPaVrHdgDXo3EZ6yCJtCiOMxW5Xz3fiUufp1sdQ==";

    public static String PUBLIC_KEY_SSH_RSA_1024 =
            "AAAAB3NzaC1yc2EAAAADAQABAAAAgQC8nIRjuQr9gGfGTdq7BL4l3s4n4qEe3w7imhv1cT5cy2HT7DXvnA2gGVmFn4izbWlFlQG1lrtMgIiiwXH/shRx+2FnqayNsOmRJ37TiA0ICjkOrdR4JaYWRafQ0TEC0+EdHl+3iJYOhw9scFpJ2M9kB6W5HJsf4gmXoGGz8SsfsQ==";

    public static String PUBLIC_KEY_SSH_RSA_2048 =
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQCjw8Wgl1usvj9LCzF1c8PufEIG11V2PHCDNlYc66ccIiojQX79st1Lbp0BJXsa2bvZLYjfqyYP5gqkX7jLslmXPN+Vew91sRTmXJTlANlm/fChHg+Fq+lQK0IKGBIn9RlPDFH+NNoUIw4LbZ4etRJuOfMwiVKsOVOYuuLjiIJTkda9eS9zrhTRUXhUuMIxBLdeJEAYve6oBpcnTKpUbTV+DYlru3Yh6lSIevhA361s65oJauNHFQLQ7Ysi9apiF5hmqt1sThv/NPM/xLwlPrSGqKZWnclJbBKaWFlCijuM7W3Q5zbcdmtvKhxEJMobu+KMbt/LVhV7kD3BBLhADKnZ";

    public static String PUBLIC_KEY_SSH_RSA_3072 =
            "AAAAB3NzaC1yc2EAAAADAQABAAABgQDzvqmjwxE3UgKOVZDoji9npAu7Uee47sdS60cTN0yx3Aj+c5IznoBLDYt7HwUcjKoj6soRJALFMGvrKe6n4H1+9jF5vrstMB40Ga8858wweehIAEzw/ONAORZdHA2y0WG8K3+bNOVSeXZwASsjbKrYcdotsZarhQtGVks6xQwd7qXUD44DDdFuWsuj5//hSSYSIgjJE3gAfeI2qVoe6Cl6gTGoK9zd+hNrYDehpN7bDgX45ulcaMw7N2kLf+Sg5QqOYL3Xdav/SeNEefNUyE058uRK8Br3WhZh5BJ2qFjzUYe20cFKHJ3gqKiY+8aor6YrDAS5AOEAEdCw1GWHJutGeApouTSqpNZf1uHspKEgLCUu6gb+i14k3YGSqUW/3fRdqmtN5qBYGvOoqgUEG1wlsxjf6lvJxSh6551MEiM3dpXBq3wniFjK56pj9nVjW0erJsOXPIqh9KeQL1dB+fzNX0r3oAog3EEl+x+V/YLE12b8MR9qnaZwyxWPGKoRE70=";

    public static String PUBLIC_KEY_SSH_RSA_4096 =
            "AAAAB3NzaC1yc2EAAAADAQABAAACAQCvoE/zGFhSYP/aXLGYl27P+Bq5KJ0E53Er163GJRZ119kgPTB17JOKEG1k25tmspoNYVVaSIM81zBi4RUIrP7ft+1wj2FlsMchrEHlrqR31HCCsPmf/YGgzaBBgL2KDNHEsnxIzyZTsY4ZGPS4LZMP8McUXfwvOFkVs12AUNH5hrB0SgMv6sor1VyW43p6u1o1w9MX5omANopOv+Rqm7In0UXNmOocOhOFYqDJVKt05+fI+fduHIwO4Wi3e0K1jK1EmC9YlIJJIz0Ce1+CyGK0Cm7lHj+W2Ea5tERO0DsK/etGbn1w8NcW9XmPVzO4vSvsMm7XrL0hIdNQZKSxas4NNwxr0TZN70T+H3WKRK9VAxCEp5IdahsSevKyrcsRnKX3mcemqJZZ+ODAarPdHemNacywzoaEt2AOSOl1PcW/sA49R4yMYHj8RS6xDv9jeA4Vogj58ynqzaB2F4fCkaV4bmgb2vL0Fkw96Tvq0+Gs902zvtDmnneicCWhNnj+3jRKZjqiQRvA3/BgYrokFGcDra4j9C1vrDVajMitcY+dr0XeA+n9ot29GSx36Fwg3j3QUhamS6/nsKTeIdmEHeym7FT6LKweKL/XcUCs+tkaJFxsJ+S1E+vF2M7SmqkNuB0S17EijZtw01v1zbzocscnfpLXo3UEfBdIe7pjT/IGtw==";

    public static String PUBLIC_KEY_ECDSA_SHA2_NISTP256_256 =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBB1ZWbuchcMR2NMc4wu4sB2sFvnxZ45tAyijmSx9pUkQ51InNU7t1qzf2p29VhwdJ7kQSX3HdUcwBP1NfUSEoFw=";

    public static String PUBLIC_KEY_ECDSA_SHA2_NISTP384_384 =
            "AAAAE2VjZHNhLXNoYTItbmlzdHAzODQAAAAIbmlzdHAzODQAAABhBEfdfV0luXJ0NJGMpeAMDZvfDjfwpC9U+YDSlgM0Gh2eCCtYCGv41G4tZd5+L1gjPEiS4Y8r+jb3JoAX6JdQfHecK6+NHpZsF0uwrn8zTfA9PT+I9nTtEyBgNWM/v/A5wQ==";

    public static String PUBLIC_KEY_ECDSA_SHA2_NISTP521_521 =
            "AAAAE2VjZHNhLXNoYTItbmlzdHA1MjEAAAAIbmlzdHA1MjEAAACFBAFrY2jC8sarrQqI13e9fDhzeUvTFt5j2krHfFfqDrP/M7L5RJzbg4jOSOly7FdOi7JhFkYaEguddhRh2DIUWKHR9ADR9/m4n9WxHR9QaVLUYUyZdQzgdtlY6KfLYJyO5PBSulMhpfDKGoycNKmr6Av1gyESAIBq+bINsgpUby+h9jkC7Q==";

    private final String description;

    private final String algorithm;

    private final String publicKey;

    private final String expected;

    public HostKeyTest(String description, String algorithm, String publicKey, String expected) {
        this.description = description;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.expected = expected;
    }

    @Before
    public void before() {
        // Add provider manually to avoid requiring jenkinsrule
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            {
                "SSH-DSS with key size 1024",
                "ssh-dss",
                PUBLIC_KEY_SSH_DSS_1024,
                "48:0b:8d:f6:6b:8d:99:11:e5:6a:02:9b:fb:f0:20:4e"
            },
            {
                "SSH-RSA with key size 1024",
                "ssh-rsa",
                PUBLIC_KEY_SSH_RSA_1024,
                "17:1d:65:4e:bd:6e:3b:e2:51:46:35:84:db:ff:c2:53"
            },
            {
                "SSH-RSA with key size 2048",
                "ssh-rsa",
                PUBLIC_KEY_SSH_RSA_2048,
                "6a:8c:88:49:9f:fe:47:3e:27:a5:c2:d4:45:6b:28:45"
            },
            {
                "SSH-RSA with key size 3072",
                "ssh-rsa",
                PUBLIC_KEY_SSH_RSA_3072,
                "29:7b:fe:5b:e3:bb:7a:28:9d:41:2a:f3:bf:95:96:2a"
            },
            {
                "SSH-RSA with key size 4096",
                "ssh-rsa",
                PUBLIC_KEY_SSH_RSA_4096,
                "1d:21:8f:0e:97:38:f8:3b:a7:a6:d6:72:f0:c2:ca:20"
            },
            {
                "ECDSA-SHA2-NISTP256 with key size 256",
                "ecdsa-sha2-nistp256",
                PUBLIC_KEY_ECDSA_SHA2_NISTP256_256,
                "a4:59:c0:2b:66:42:24:df:36:ca:d8:55:ae:b9:65:5b"
            },
            {
                "ECDSA-SHA2-NISTP384 with key size 384",
                "ecdsa-sha2-nistp384",
                PUBLIC_KEY_ECDSA_SHA2_NISTP384_384,
                "ec:79:dd:bd:30:26:df:ce:84:5e:83:c1:8b:28:b8:ff"
            },
            {
                "ECDSA-SHA2-NISTP521 with key size 521",
                "ecdsa-sha2-nistp521",
                PUBLIC_KEY_ECDSA_SHA2_NISTP521_521,
                "27:a9:ed:e3:8e:17:00:e1:db:a2:85:e1:f8:ab:f5:60"
            }
        });
    }

    @Test
    public void testPublicKeyValidation() {
        String fingerprint = new HostKey(algorithm, Base64.getDecoder().decode(publicKey)).getFingerprint();
        assertEquals(description, expected, fingerprint);
    }
}
