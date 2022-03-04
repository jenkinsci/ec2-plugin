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

    /* From old configs */
    @Deprecated
    private transient Map<String, Boolean> minimumNoInstancesActiveTimeRangeDays;

    private Boolean monday;
    private Boolean tuesday;
    private Boolean wednesday;
    private Boolean thursday;
    private Boolean friday;
    private Boolean saturday;
    private Boolean sunday;


    @DataBoundConstructor
    public MinimumNumberOfInstancesTimeRangeConfig() {
    }

    protected Object readResolve() {
        if (minimumNoInstancesActiveTimeRangeDays != null && !minimumNoInstancesActiveTimeRangeDays.isEmpty()) {
            this.monday = minimumNoInstancesActiveTimeRangeDays.get("monday");
            this.tuesday = minimumNoInstancesActiveTimeRangeDays.get("tuesday");
            this.wednesday = minimumNoInstancesActiveTimeRangeDays.get("wednesday");
            this.thursday = minimumNoInstancesActiveTimeRangeDays.get("thursday");
            this.friday = minimumNoInstancesActiveTimeRangeDays.get("friday");
            this.saturday = minimumNoInstancesActiveTimeRangeDays.get("saturday");
            this.sunday = minimumNoInstancesActiveTimeRangeDays.get("sunday");
        }
        return this;
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
            throw new IllegalArgumentException("Value " + value + " is not valid time format, ([12:34 AM] or [23:45])");
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

    public Boolean getMonday() {
        return monday;
    }

    @DataBoundSetter
    public void setMonday(Boolean monday) {
        this.monday = monday;
    }

    public Boolean getTuesday() {
        return tuesday;
    }

    @DataBoundSetter
    public void setTuesday(Boolean tuesday) {
        this.tuesday = tuesday;
    }

    public Boolean getWednesday() {
        return wednesday;
    }

    @DataBoundSetter
    public void setWednesday(Boolean wednesday) {
        this.wednesday = wednesday;
    }

    public Boolean getThursday() {
        return thursday;
    }

    @DataBoundSetter
    public void setThursday(Boolean thursday) {
        this.thursday = thursday;
    }

    public Boolean getFriday() {
        return friday;
    }

    @DataBoundSetter
    public void setFriday(Boolean friday) {
        this.friday = friday;
    }

    public Boolean getSaturday() {
        return saturday;
    }

    @DataBoundSetter
    public void setSaturday(Boolean saturday) {
        this.saturday = saturday;
    }

    public Boolean getSunday() {
        return sunday;
    }

    @DataBoundSetter
    public void setSunday(Boolean sunday) {
        this.sunday = sunday;
    }

    public boolean getDay(String day) {
        switch (day.toLowerCase()) {
        case "monday": return Boolean.TRUE.equals(this.monday);
        case "tuesday": return Boolean.TRUE.equals(this.tuesday);
        case "wednesday": return Boolean.TRUE.equals(this.wednesday);
        case "thursday": return Boolean.TRUE.equals(this.thursday);
        case "friday": return Boolean.TRUE.equals(this.friday);
        case "saturday": return Boolean.TRUE.equals(this.saturday);
        case "sunday": return Boolean.TRUE.equals(this.sunday);
        default: throw new IllegalArgumentException("Can only get days");
        }
    }
}
