package hudson.plugins.ec2;

import static org.junit.jupiter.api.Assertions.fail;

import hudson.plugins.ec2.util.FIPS140Utils;
import java.security.Security;
import java.util.Arrays;
import java.util.Collection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junitpioneer.jupiter.SetSystemProperty;

@SetSystemProperty(key = "jenkins.security.FIPS140.COMPLIANCE", value = "true")
class EC2CloudPrivateKeyWithFIPSTest {

    public static String PRIVATE_KEY_DSA_1024 =
            """
            -----BEGIN DSA PRIVATE KEY-----"
            MIIBuwIBAAKBgQDdxPbkTmoDD5cK4W9bE5OVKO1Qu7vw32ZOAFHEdOCjK/JxLEyu
            yV/nlywOmG3fjMKjQdaFDggg9TAZx6Kjor/lXSnpxbWvS5/Blv6QXWMsJZCmVSen
            lm4Qk3bKv1Y9P6SguJQXi+RmSQW5otoF5PikPK3Kd72LoWLioFfOUyQkuQIVAIfp
            1tgKiJUxzGooCovDKjNeiXMTAoGAR/BjnA3F2QYUlPVuQy9K2v5epJ020xHzJp/q
            h71qhTOTMgqT+hvsijfuK4yGwa0S+Lw1Li7sr0uQN+Bk6ayPyRxx+Lo91tz74DY3
            hkDwL8ocWjD7LIAo+Qdzc7Bme398XDHY7GVBrwcK6/YMCtQUevhxkVpd4apjsJtd
            wOK2SvMCgYEAsZ5gj+BrmqEGVOD8PfKbJH5H3BOLptvhdwJ1jNVnkH8CVpwWs6+R
            1ZzW3VVGSEqChuL1YvchfBSRRtxlVvUbFeSvxJPz2f+KmA8AXnJoStIY2WL1MFhs
            3ag3NN0XYO9X0pmwT109VUh3vFRtwzyG+YYUTx5pgv/yuEr7qMirt2kCFD7Av2K
            uaL7v9mj+BwjsANb3l9A"
            -----END DSA PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_RSA_1024 =
            """
            -----BEGIN RSA PRIVATE KEY-----
            MIICXQIBAAKBgQC/+fn2CosvVV26//CaY4tLHWxoK1XjSp2HYF6lxDgMWw4Rq+Rx
            ds62LC2oHjsO/YHtKmwh+C61jk0UNAENer5V9SiHCStzB/l8qLG4jHZb4JggpRsn
            ArTy1M0//0rhHbm6Adfr/Lr5aO3PM+TvusTpY3ZJmh7EUFSNyMEUCamfywIDAQAB
            AoGAWG3hQgBhVJBR+I1kWvl3dEY9ZU5w5Z29KlqtvlXAK5DVzjYLqGg9l5SKA2LJ
            eYI0kvZzkMItYdwGjUPXKEpd2ZRv8HZlyPWYhMilguT2Wd4cW0xNE+r9Rjgww9g/
            XaPycCKlIQlfWosWZaLK7hgmVDfPvIjVLBDpQrkdISy6o2ECQQD80i/oKkv4rkQ7
            TId6aA8PYroAcfSOOJJg1RyVYjEo2e1KD7HOkbzHtn7mnJ4SAbLnsHIZZQRLtORl
            wMvZDW53AkEAwmPvWHIl6WRNNfuztluDflrwdSst+hbxhQpAcPidNEV+UV1Ye/jV
            JpWkJNgaAMiJB0oP39Mu2nubZCfZHhdKTQJBAKuPFeM9kIAYAUUcEXMG2fFe1Uko
            CwPXb7014Eeeci1+dH8lV0sNqkT7mfFzpfAiJv0BxutkmR2mirZhtfJ8ItECQBB8
            VTIVDC4M+ZdYb1dJz48Ju1bUgKOzCmyT//8UtpBWTG4uEnEBG2KYUkFlql7iouxh
            VZNP36tbzEPkNT+eDgkCQQDlTonqWmXPaCij3LVbmBwUb6asaxmmf7fh+T+Vaizo
            mSwthk8w562jnJgQ5bsOA+PpgPckJJjqp5KbmAIw3xCb
            -----END RSA PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_RSA_2048 =
            """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEogIBAAKCAQEAuKaL32E+cTIvWlfX6U4Q+ky+9/PREwVw/HA0nNWdf1bV8qxU
            8XQh/JFHuGr8Jr5i64VE70rlvE/q/jM3m6G+s5INYjG5x0VXXtNEQoab12/WELFJ
            S/G8RETtOZmkQJ4bBopNCtkEEw5x5JaFAzgdkIhXUc6ebjmNmJdaQ0BQWq6Sqa1x
            wv5KfE5I7zZnXJtVV29SXDdnK0YdKz/f0oiXTceNNOCkP/FePfS95/1j2YTt1TQg
            YaU3qXoZxs/eeH1LTyQVVmdeoUxRoGQB2U5wFXC2XAzZ1cFcWw+pZ6HDOiRnxV4c
            IAnwvqoGF999Mzsa2QK3ZG4yH/GO6YYBxVgcQwIDAQABAoIBADy0tIOKCudYIm1H
            N/rB6Z8AoEAQboocXdsAYKu3JwJ4X/paYcrH7WyFrtiYg7GRIiBgPhuVuhXBCHbu
            C7gk4vdSawf/ZR54U5MfTe+5JX8ci3oNbxWCseyX5I3tTyzXTfdGfLG2Szqgox0N
            x0kZp86epGaG0VtXnI+wIsK9YC2Pa42bakBZnFr8+zzHNhujwc9B2CAuFyE8KUda
            BONWQpPe+jiPgKOixb6KXzNk+QGRdi4kPIY4yToz+aWjMKPtEZDJuEc0zjv+uSaA
            cdxBu2hc1OLflQtbfamoEM7KXDYtVrWlGCsGpVFoPzdD55okFAgrtdIFBajfjTHZ
            BlOJNHECgYEA7+OdWHO80XJDXVz23JPpJBCi2kxL0WTqqApQ3jVphWA3Bb5fDb5Q
            J7Ek5je92BipPJkkN0THoChVmF7hLTI19YSo6tcQ62POPXu0mPENeKelWvI7YW8O
            +CpIvxl9RRwjkw2jLjWMInVVoCwU+//UBSUpW9cIMa7Joy0mDOS2swcCgYEAxQ04
            zoelsn3G4jw4bSo+yBbHDu8TuqnHVgE33HS13ysdxqeOeh6jvUVzK2KKcUZakQpS
            LZGfOF51OwY1YmpOlsLQalZ/8ntuefo5rtuFhu7lgWPrX4KQimNTCnJR5F0NV/Md
            1WovSdSKBilZErOn7qgjDBFkfBUtpZ43gnmlkeUCgYAV06T+ZlF40Se19/5yJXci
            E+1tZWHEpKUBMycWgM+gFhgLir3FV1qdse2EkO/SGLRVUi3MZZKwTNs06PUeEqJ8
            O1zPOVBNyp/6UiYlgFFUeBSAiOfEPsGi7N3/nUcboarO93+wdajRfdGTqE8kequE
            6FOyCoexVZD9Kt96btj8wwKBgCSJpxbkoBzQpagdcnkLdEi1sINcYVQjVwrjfvAp
            0+9ll0fWmdybAdF+pzRMOU93tCNgvowkjFlval1fcVamT5w002BkWaUkrf+AHmIF
            4mR6t6OeW26CTzrZ3/P37qdhea/tLIL+BXazKkSqNhH5rhHaq2T5dKBtbOFgzPos
            hD7hAoGAcOsgmYOU+AztBOic1mnRpOeTxR/A0QGrB/9wfzfwhp6lVNnkrnDFonHx
            264wgC6h9O2mLZk/85CAaod0YOv2wUziRsEoGDxnSR+QlS1VjDc8uau7R60l7flf
            7VOBXk8FUPgW87bydSqXfwgGqoG/WAmLsqqeXDe5DYg9wV9vPnk=
            -----END RSA PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_RSA_3072 =
            """
            -----BEGIN RSA PRIVATE KEY-----
            MIIG4wIBAAKCAYEAxiHReDpOX+whDtLOFJ1lcisLs3qgnlrN2EocdQ6Ir64MkrE6
            9xnmFW2ePzyxpamWKGAkj5Bh97/4ShCVYr7lfzTy8KrrOOyy6Dxv7FL+RjljGNjE
            PAJVBai4cbUgJwIugoxXZimDRaeTpQTmDLxpX0mbALK5A0lKK8RWfiz+JQ9C0e8R
            +dn1emimlu4JuetJshTKCkpELc1t3jgFK4rhWZMQTqCG3ahIffrN9LoBjH3h958t
            urE1LIDwNGRbGLT4KdW/fvLpgOrCWxGql+bkbYYskAqnVOV0+QItjUjjgPp21SaF
            2l8qliF76q7VhVHSK2VQvWr4drT3RzbFdNusrSETYR8CKxk+ls+Fd3lwmnUGAIzH
            GoOpMyfB1x+/NFSAAeDh8HN1D/C6tdmTqV2hgbQGdiaaeCao69v0CUoeAqj9YMKi
            hjOU+3VpAZOGng9ZhFcr1S8wG2jWqPf4yt6WnJBBRL8hONquAz/8Jgq5dVWkXo/y
            a/7RDI14LH+JnXTdAgMBAAECggGACPFy1otEiWDhAchzI+FnjavdZy20UqGE+WvS
            9VesGh3wPwN/8V0PvSI0X+SvR2vXCsiC2KDAQ0y8z38IdytoXtcMK4p+JpTMjd4D
            K/bNDRdps9qTn6Uiov2WzGR2veauecG4RN9qfPo6QwXBv+EV8oah1/oOjtQ/5FIu
            7fAS9/zvMQb/rThGM88bIeSUYiTO2ppvSjVITDb3FsOO5tm/H+oa9NRFx3s2lnUm
            gb2O9Kv5R1I94AJABQZm5pPXEXpC8DJu/1/n9wsKY0BEg38zc3Zq/yfHNmv9Ysz0
            rychuehMpKxTTU7+fUJMqpshm+Pdmo4jc+Mpfx+fc5+hKHI/9fP9sfONyyUjSKwL
            YTH3RWVuVT+9vzhPs6JYkc/ZXgzvgcNBTqTENTkewHqf+SeDfYt+YasBDizupmMc
            aGLIIyxnxUmms30hwmcxtBTIDhACLhw/FS/1lnlKQhwOCeMwtSyFUqMfnCPer+rO
            8jU1g5Lfs7sB1hlOr2B4hSIrpafTAoHBAP7biHCa3fRI/Jy/Cf/1laEWEoBG8HBH
            VOzW9bQUrc4bmyohqPXTL1jQA42/095hSG+3XmJEHiXycAGWXn5ilh234ijlrEzl
            KqcLDMP2iEGtzRgwiG50j6/xN5JKBJIw/M4eDEGyY9psfCgp8na1ztuJ8sHL0e5N
            fsB7KKVg4TU16X5kGuNNMUgMxFYXm9vskADCQ/AlyFdUpFglPkwU8GuAwlfLExDs
            kWsxryt8Xe2V+4qF4KbYafuZ/eJwEF7dnwKBwQDHBTBWQK3COoWMegX+4L1pWK6L
            KUuJhEffxeU8jbAxXHASfquRV3vF3jDYh8Y9kJgtgIMxbfSdBSwUpvvVglsgKvUF
            hM5X6eA7HY9eT8Ps43UyM5JCPofIpJZIx4y/OAR9hzs7VQjm8Eq0Ke3rm3bEbQlZ
            Vrm5fVxdKMq90wJVi2Z4VefXpWttv33N0BvXDwVGKLMYtkKAdVi4J13FxiaHJeyl
            Dr72ogO/HVsnzZlhyB3V+5jV6XQs7u3ITlIZpAMCgcEAv0m7oPk8euyFXoktUkbc
            ZioQ/ONB+KQxpAq8JMwYoEisL/VPwiMeuIR5Bl3jAlj2a5OwbgU+s7DCTQ62IhqR
            HgE06QlqR9UCLJrom/Vg1BtFg1B6Np2ac66TzWNtBuVp+rMm8/CXbgxbLDI/4MYZ
            W0KxSLBZA4p7BrHqEicjIjMy7EDqxYzc3n1mqE/UFj/63fbx00AonRPUvqxFlAlr
            YuUj+Y1c5CkMBO8n0XXpcjhOsuxFcDWjZstwehMu1mV1AoHAexbNd3sXPHpfYKuT
            i7jJzR7pDO6kZk/m+BJ4HgRvxYerVPT8/a5CwfUS9si6phcI15OVEHw1/utVAQzp
            0nqGC5Yl5pzl1d+zLDyzEBx7S8a+FCdrPQdZiZGp1Sd9+EIYHN8HlkGYeOSC/3yz
            RrXnNcNONe/6fCt5dbCl+9NGrUvDO4e+FVSc5cq6bxFYNqF2nJbNdeo7pSFulq7a
            Q1izOYEOJGPDXdyEPq5UU4DIbX6MXWz3cM2raaL4c5tlEbCrAoHAPa5Es7NUrA9p
            1UhLIihKJtUz1jpQTLm/yfEW3lwKosCmJVFph/2HkdPR2d6iielrMdpN3Cklpf72
            QDT6IROxw4kQ2r4cJV2mdzfxiw1sDlgoeKwEpKeaKq3zHMWH4GinFSdCrxm8DABK
            C1A1t+Y6MjfssXmE2UibzpOaeEEgBlfehkFrS/RKlpb14I6Pnt1gNsEhenvQS3Q2
            LfA3a4Vx+HNkoeqTfGhjbtZnae2C3RWMrn6FMPA2fXeVVwpQVMTF
            -----END RSA PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_RSA_4096 =
            """
            -----BEGIN RSA PRIVATE KEY-----
            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
            -----END RSA PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_ECDSA_256 =
            """
            -----BEGIN EC PRIVATE KEY-----
            MHcCAQEEICK/NJcJmy18Co6iL023g9/4K+6CNqLJBAHE8/sGYarLoAoGCCqGSM49
            AwEHoUQDQgAE+P+p3NmQK4QnrFNgeiNOwjGkikQ3Gf3yuf8O5WbfRbUFunP/dtnp
            Ow89uIagAaVuR44ao60eUST/YeHB2bgnnA==
            -----END EC PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_ECDSA_384 =
            """
            -----BEGIN EC PRIVATE KEY-----
            MIGkAgEBBDDPRHa8rL2Gx9vmI70pjQJbnyDxU0TOkwqj7ILfsv90fSsO19qQARhB
            w7gH1qOkclmgBwYFK4EEACKhZANiAAR2v/iys6np24cCT+/5Hi/pmjsCpj9OQaUP
            1IcnuLQYNZfIuqSbIdO9Y5zH1rCl5TAIvJXS7v9qcfLr3gSyl9hJmVfsovV1iuWj
            yMo7EYhtYhUAjIrtCV9mhL5Nd6zwnbg=
            -----END EC PRIVATE KEY-----
            """;

    public static String PRIVATE_KEY_ECDSA_521 =
            """
            -----BEGIN EC PRIVATE KEY-----
            MIHcAgEBBEIBVoBkpQalEMfeJGPQubTF3EyEqc9uveqRGmHtzjsK1ZyXZPqGfTdA
            nyS22O2PDwyUh/tmQBZ98XY5f6zmf8+NIimgBwYFK4EEACOhgYkDgYYABACVlF0S
            3l9PotmDxWM1KK0aob/HMBzZzFry0dygTa0UBvHHHpCYparbvWybIDv8rcf/nNnG
            1cWThWuMadULhkKZQQAgu6NEyx8LPpE72GG709VVThwD78f7cK0qG1nghPTvD9Mj
            382fy5Hz288ZSFx3dk3CwJQ+81opuN+tMYWgCDh+gQ==
            -----END EC PRIVATE KEY-----
            """;

    @BeforeAll
    static void setUp() {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
            {"DSA with key size 1024", PRIVATE_KEY_DSA_1024, false},
            {"RSA with key size 1024", PRIVATE_KEY_RSA_1024, false},
            {"RSA with key size 2048", PRIVATE_KEY_RSA_2048, true},
            {"RSA with key size 3072", PRIVATE_KEY_RSA_3072, true},
            {"RSA with key size 4096", PRIVATE_KEY_RSA_4096, true},
            {"ECDSA with key size 256", PRIVATE_KEY_ECDSA_256, true},
            {"ECDSA with key size 384", PRIVATE_KEY_ECDSA_384, true},
            {"ECDSA with key size 521", PRIVATE_KEY_ECDSA_521, true}
        });
    }

    @ParameterizedTest
    @MethodSource("data")
    void testPrivateKeyValidation(String description, String privateKey, boolean isValid) {
        try {
            FIPS140Utils.ensurePrivateKeyInFipsMode(privateKey);
            if (!isValid) {
                fail(description + " should not be valid");
            }
        } catch (IllegalArgumentException e) {
            if (isValid) {
                fail(description + " should be valid");
            }
        }
    }
}
