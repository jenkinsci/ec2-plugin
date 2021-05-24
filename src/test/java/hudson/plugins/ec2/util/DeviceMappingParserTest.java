/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2.util;

import com.amazonaws.services.ec2.model.BlockDeviceMapping;
import com.amazonaws.services.ec2.model.EbsBlockDevice;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DeviceMappingParserTest {

    @Test
    public void testParserWithAmi() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdb").withEbs(new EbsBlockDevice().withSnapshotId("snap-7eb96d16")));

        String customDeviceMappings = "/dev/sdb=snap-7eb96d16";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithTermination() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdc").withEbs(new EbsBlockDevice().withSnapshotId("snap-7eb96d16").withVolumeSize(80).withDeleteOnTermination(false)));

        String customDeviceMappings = "/dev/sdc=snap-7eb96d16:80:false";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithIo() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdc").withEbs(new EbsBlockDevice().withSnapshotId("snap-7eb96d16").withVolumeSize(80).withDeleteOnTermination(false).withVolumeType("io1").withIops(100)));

        String customDeviceMappings = "/dev/sdc=snap-7eb96d16:80:false:io1:100";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithSize() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdd").withEbs(new EbsBlockDevice().withVolumeSize(120)));

        String customDeviceMappings = "/dev/sdd=:120";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithEncrypted() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdd").withEbs(new EbsBlockDevice().withVolumeSize(120).withEncrypted(true)));

        String customDeviceMappings = "/dev/sdd=:120::::encrypted";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithThroughput() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdd").withEbs(new EbsBlockDevice().withVolumeSize(120).withThroughput(1000)));

        String customDeviceMappings = "/dev/sdd=:120:::::1000";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    public void testParserWithMultiple() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdd").withEbs(new EbsBlockDevice().withVolumeSize(120).withEncrypted(true)));
        expected.add(new BlockDeviceMapping().withDeviceName("/dev/sdc").withEbs(new EbsBlockDevice().withVolumeSize(120)));

        String customDeviceMappings = "/dev/sdd=:120::::encrypted,/dev/sdc=:120";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }
}
