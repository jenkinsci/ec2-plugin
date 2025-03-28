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
package hudson.plugins.ec2;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/*
 * Note this is used only to handle the metadata for older versions of the ec2-plugin. The current
 * versions use the Amazon APIs for this. See PluginImpl for where this is used.
 */
public class InstanceTypeConverter implements Converter {

    private static final Map<String, InstanceType> TYPICAL_INSTANCE_TYPES = new HashMap<>();

    static {
        TYPICAL_INSTANCE_TYPES.put("DEFAULT", InstanceType.M1_SMALL);
        TYPICAL_INSTANCE_TYPES.put("LARGE", InstanceType.M1_LARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE", InstanceType.M1_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("MEDIUM_HCPU", InstanceType.C1_MEDIUM);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_HCPU", InstanceType.C1_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_HMEM", InstanceType.M2_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_HMEM_M3", InstanceType.M3_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_DOUBLE_HMEM", InstanceType.M2_2_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_QUAD_HMEM", InstanceType.M2_4_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_QUAD_HMEM_M3", InstanceType.M3_2_XLARGE);
        TYPICAL_INSTANCE_TYPES.put("XLARGE_CLUSTER_COMPUTE", InstanceType.CC1_4_XLARGE);
    }

    @Override
    public boolean canConvert(Class type) {
        return InstanceType.class == type;
    }

    @Override
    public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
        InstanceType instanceType = (InstanceType) source;
        writer.setValue(instanceType.name());
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
        InstanceType instanceType = null;

        String stringValue = reader.getValue();

        try {
            instanceType = InstanceType.valueOf(stringValue);
        } catch (IllegalArgumentException e) {
            instanceType = TYPICAL_INSTANCE_TYPES.get(stringValue.toUpperCase());
        }

        return instanceType;
    }
}
