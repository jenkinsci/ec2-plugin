package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.NonNull;

public enum EbsEncryptRootVolume {
    DEFAULT("Based on AMI", null),
    ENCRYPTED("Encrypted", true),
    UNENCRYPTED("Not Encrypted", false);

    private final String displayText;
    private final Boolean value;

    EbsEncryptRootVolume(String displayText, Boolean value) {
        this.displayText = displayText;
        this.value = value;
    }

    @NonNull
    public String getDisplayText() {
        return displayText;
    }

    public Boolean getValue() {
        return value;
    }

}
