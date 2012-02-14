package hudson.plugins.ec2;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.ec2.model.InstanceType;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;

public class InstanceTypeConverter implements Converter {

	private static final Map<String, InstanceType> TYPICA_INSTANCE_TYPES = new HashMap<String, InstanceType>();
	
	static {
		TYPICA_INSTANCE_TYPES.put("DEFAULT", InstanceType.M1Small);
		TYPICA_INSTANCE_TYPES.put("LARGE", InstanceType.M1Large);
		TYPICA_INSTANCE_TYPES.put("XLARGE", InstanceType.M1Xlarge);
		TYPICA_INSTANCE_TYPES.put("MEDIUM_HCPU", InstanceType.C1Medium);
		TYPICA_INSTANCE_TYPES.put("XLARGE_HCPU", InstanceType.C1Xlarge);
		TYPICA_INSTANCE_TYPES.put("XLARGE_HMEM", InstanceType.M2Xlarge);
		TYPICA_INSTANCE_TYPES.put("XLARGE_DOUBLE_HMEM", InstanceType.M22xlarge);
		TYPICA_INSTANCE_TYPES.put("XLARGE_QUAD_HMEM", InstanceType.M24xlarge);
		TYPICA_INSTANCE_TYPES.put("XLARGE_CLUSTER_COMPUTE", InstanceType.Cc14xlarge);
	}
	
	public boolean canConvert(Class type) {	
		return InstanceType.class == type;
	}

	public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
		InstanceType instanceType = (InstanceType) source;
		writer.setValue(instanceType.name());
	}

	public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
		InstanceType instanceType = null;
		
		String stringValue = reader.getValue();
		
		try {
			instanceType = InstanceType.valueOf(stringValue);
		} catch (IllegalArgumentException e) {
			instanceType = TYPICA_INSTANCE_TYPES.get(stringValue.toUpperCase());
		}
		
		return instanceType;
	}

}
