package hudson.plugins.ec2.ssh;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class HostKeyVerifierImplTest {
    final static String key = "AAAAB3NzaC1yc2EAAAADAQABAAACAQCfNQmsUa7FChPiiCioV8Oa46DwJpUf/Huljbgz6Mn+AIl+dRs7fmjkD1WbcViV7himWB4s+42eyZqctgEyRsrdC1FFagT0NfpRgwl1btgN7MFUQB5IN21UUJrNdINmWVT1x2ffa4QDfoIqZEmE3H09UQQZoKT2qglxucX+Nv+tVhD+UJTBS01z20zs1nAJDyBcG1RNXhGny1cidtI51FyQ5wdgOxscLvJ1xeQglhgI0WXrMpUofIFu9Kw54lAVHtU9Ym++jOcPd2HEkq9ISo5bw7MX4OZvbCwQKgqYzGEdVmrv4e571Z7dJIgI4zEwDn8yiJ/3Zs1NZwZ34/Mtq+j1CacLTUQmpNo3t1bw5bq3gLyjal5/mTjPr2L+jztGiPwxDW63Vr6RoyRXcyylwPB40L2TqWMmkdWAUFnwXw10ZwWt6oojz0qQjq/gaV/eoAajr6zAj0O6gFbpR/cQj2Il2U5Yg3Mo3Ow6pt3aAQxDj26PR4RIIL9zbIfJPtcw/VnmXciR/0Clny/HuxxmrnTJvw5zCaUenQPQaI6jMRJoND+g24ncWPKy1da5VLKb2lwdX1DC0FHawt2zr5XDs6TFbFYb+vjiwLNgzlZRr3SzOJoQ7Ok+ie8diKOMeEwPTzknPWYUSmCJgg3sA95Z0257o9bs2ZALYeNU1IGGlTQPmw==";
    final static String fp = "51:16:da:3b:46:5c:4a:47:1b:05:12:db:88:15:ea:79";

    @Test
    public void testVerifyFail() throws Exception {
        HostKeyVerifierImpl impl = new HostKeyVerifierImpl("");
        assertFalse(impl.verifyServerHostKey("", 0, null, key.getBytes("UTF-8")));
    }

    @Test
    public void testVerifyTrue() throws Exception {
        HostKeyVerifierImpl impl = new HostKeyVerifierImpl(fp);
        assertTrue(impl.verifyServerHostKey("", 0, null, key.getBytes("UTF-8")));
    }
}