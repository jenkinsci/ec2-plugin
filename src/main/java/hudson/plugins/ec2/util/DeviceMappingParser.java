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

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;

public class DeviceMappingParser {

    private DeviceMappingParser() {}

    public static List<BlockDeviceMapping> parse(String customDeviceMapping) {

        List<BlockDeviceMapping> deviceMappings = new ArrayList<>();

        for (String mapping : customDeviceMapping.split(",")) {
            String[] mappingPair = mapping.split("=");
            String device = mappingPair[0];
            String blockDevice = mappingPair[1];

            BlockDeviceMapping.Builder deviceMappingBuilder =
                    BlockDeviceMapping.builder().deviceName(device);

            if (blockDevice.equals("none")) {
                deviceMappingBuilder.noDevice("none");
            } else if (blockDevice.startsWith("ephemeral")) {
                deviceMappingBuilder.virtualName(blockDevice);
            } else {
                deviceMappingBuilder.ebs(parseEbs(blockDevice));
            }

            deviceMappings.add(deviceMappingBuilder.build());
        }

        return deviceMappings;
    }

    // [<snapshot-id>]:[<size>]:[<delete-on-termination>]:[<type>]:[<iops>]:[encrypted]:[throughput]
    private static EbsBlockDevice parseEbs(String blockDevice) {

        String[] parts = blockDevice.split(":");

        EbsBlockDevice.Builder ebsBuilder = EbsBlockDevice.builder();
        if (!getOrEmpty(parts, 0).isBlank()) {
            ebsBuilder.snapshotId(parts[0]);
        }
        if (!getOrEmpty(parts, 1).isBlank()) {
            ebsBuilder.volumeSize(Integer.valueOf(parts[1]));
        }
        if (!getOrEmpty(parts, 2).isBlank()) {
            ebsBuilder.deleteOnTermination(Boolean.valueOf(parts[2]));
        }
        if (!getOrEmpty(parts, 3).isBlank()) {
            ebsBuilder.volumeType(parts[3]);
        }
        if (!getOrEmpty(parts, 4).isBlank()) {
            ebsBuilder.iops(Integer.valueOf(parts[4]));
        }
        if (!getOrEmpty(parts, 5).isBlank()) {
            ebsBuilder.encrypted(parts[5].equals("encrypted"));
        }
        if (!getOrEmpty(parts, 6).isBlank()) {
            ebsBuilder.throughput(Integer.valueOf(parts[6]));
        }

        return ebsBuilder.build();
    }

    private static String getOrEmpty(String[] arr, int idx) {
        if (idx < arr.length) {
            return arr[idx];
        } else {
            return "";
        }
    }
}
