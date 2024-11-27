package hudson.plugins.ec2;

public enum Tenancy {
    Default("default"),
    Dedicated("dedicated"),
    Host("host");

    private String value;

    private Tenancy(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return this.value;
    }

    public static Tenancy fromValue(String value) {
        if (value != null && !"".equals(value)) {
            Tenancy[] var1 = values();
            int var2 = var1.length;

            for (int var3 = 0; var3 < var2; ++var3) {
                Tenancy enumEntry = var1[var3];
                if (enumEntry.toString().equals(value)) {
                    return enumEntry;
                }
            }

            throw new IllegalArgumentException("Cannot create enum from " + value + " value!");
        } else {
            throw new IllegalArgumentException("Value cannot be null or empty!");
        }
    }

    /**
     * For backwards compatibility.
     * @param useDedicatedTenancy whether or not to use a tenancy to establish a connection.
     * @return an {@link Tenancy} based on provided parameters.
     */
    public static Tenancy backwardsCompatible(boolean useDedicatedTenancy) {
        if (useDedicatedTenancy) {
            return Dedicated;
        } else {
            return Default;
        }
    }
}
