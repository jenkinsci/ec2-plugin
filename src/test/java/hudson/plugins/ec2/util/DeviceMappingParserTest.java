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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;

class DeviceMappingParserTest {

    @Test
    void testParserWithAmi() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdb")
                .ebs(EbsBlockDevice.builder().snapshotId("snap-7eb96d16").build())
                .build());

        String customDeviceMappings = "/dev/sdb=snap-7eb96d16";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithTermination() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdc")
                .ebs(EbsBlockDevice.builder()
                        .snapshotId("snap-7eb96d16")
                        .volumeSize(80)
                        .deleteOnTermination(false)
                        .build())
                .build());

        String customDeviceMappings = "/dev/sdc=snap-7eb96d16:80:false";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithIo() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdc")
                .ebs(EbsBlockDevice.builder()
                        .snapshotId("snap-7eb96d16")
                        .volumeSize(80)
                        .deleteOnTermination(false)
                        .volumeType("io1")
                        .iops(100)
                        .build())
                .build());

        String customDeviceMappings = "/dev/sdc=snap-7eb96d16:80:false:io1:100";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithSize() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdd")
                .ebs(EbsBlockDevice.builder().volumeSize(120).build())
                .build());

        String customDeviceMappings = "/dev/sdd=:120";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithEncrypted() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdd")
                .ebs(EbsBlockDevice.builder().volumeSize(120).encrypted(true).build())
                .build());

        String customDeviceMappings = "/dev/sdd=:120::::encrypted";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithThroughput() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdd")
                .ebs(EbsBlockDevice.builder().volumeSize(120).throughput(1000).build())
                .build());

        String customDeviceMappings = "/dev/sdd=:120:::::1000";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }

    @Test
    void testParserWithMultiple() {
        List<BlockDeviceMapping> expected = new ArrayList<>();
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdd")
                .ebs(EbsBlockDevice.builder().volumeSize(120).encrypted(true).build())
                .build());
        expected.add(BlockDeviceMapping.builder()
                .deviceName("/dev/sdc")
                .ebs(EbsBlockDevice.builder().volumeSize(120).build())
                .build());

        String customDeviceMappings = "/dev/sdd=:120::::encrypted,/dev/sdc=:120";
        List<BlockDeviceMapping> actual = DeviceMappingParser.parse(customDeviceMappings);
        assertEquals(expected, actual);
    }
}
