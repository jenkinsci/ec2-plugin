package hudson.plugins.ec2.util;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MinimumNumberOfInstancesTimeRangeConfig {

    private String minimumNoInstancesActiveTimeRangeFrom;
    private String minimumNoInstancesActiveTimeRangeTo;
    private Map<String, Boolean> minimumNoInstancesActiveTimeRangeDays;

    @DataBoundConstructor
    public MinimumNumberOfInstancesTimeRangeConfig() {
    }

    private static Map<String, Boolean> parseDays(JSONObject days) {
        Map<String, Boolean> map = new HashMap<>();
        map.put("monday", days.getBoolean("monday"));
        map.put("tuesday", days.getBoolean("tuesday"));
        map.put("wednesday", days.getBoolean("wednesday"));
        map.put("thursday", days.getBoolean("thursday"));
        map.put("friday", days.getBoolean("friday"));
        map.put("saturday", days.getBoolean("saturday"));
        map.put("sunday", days.getBoolean("sunday"));
        return map;
    }

    private static LocalTime getLocalTime(String value) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        } catch (DateTimeParseException e) {
            try {
                return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
            } catch (DateTimeParseException ignore) {
            }
        }
        return null;
    }

    public static void validateLocalTimeString(String value) {
        if (getLocalTime(value) == null) {
            throw new IllegalArgumentException("Value " + value + " is not valid time format, ([h:mm a] or [HH:mm])");
        }
    }

    public String getMinimumNoInstancesActiveTimeRangeFrom() {
        return minimumNoInstancesActiveTimeRangeFrom;
    }

    @DataBoundSetter
    public void setMinimumNoInstancesActiveTimeRangeFrom(String minimumNoInstancesActiveTimeRangeFrom) {
        validateLocalTimeString(minimumNoInstancesActiveTimeRangeFrom);
        this.minimumNoInstancesActiveTimeRangeFrom = minimumNoInstancesActiveTimeRangeFrom;
    }

    public LocalTime getMinimumNoInstancesActiveTimeRangeFromAsTime() {
        return getLocalTime(minimumNoInstancesActiveTimeRangeFrom);
    }

    public String getMinimumNoInstancesActiveTimeRangeTo() {
        return minimumNoInstancesActiveTimeRangeTo;
    }

    @DataBoundSetter
    public void setMinimumNoInstancesActiveTimeRangeTo(String minimumNoInstancesActiveTimeRangeTo) {
        validateLocalTimeString(minimumNoInstancesActiveTimeRangeTo);
        this.minimumNoInstancesActiveTimeRangeTo = minimumNoInstancesActiveTimeRangeTo;
    }

    public LocalTime getMinimumNoInstancesActiveTimeRangeToAsTime() {
        return getLocalTime(minimumNoInstancesActiveTimeRangeTo);
    }

    public Map<String, Boolean> getMinimumNoInstancesActiveTimeRangeDays() {
        return minimumNoInstancesActiveTimeRangeDays;
    }

    @DataBoundSetter
    public void setMinimumNoInstancesActiveTimeRangeDays(JSONObject minimumNoInstancesActiveTimeRangeDays) {
        this.minimumNoInstancesActiveTimeRangeDays = parseDays(minimumNoInstancesActiveTimeRangeDays);
    }
}
