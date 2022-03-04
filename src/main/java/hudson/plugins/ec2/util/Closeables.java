package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Closeables {
    private static Logger log = Logger.getLogger(Closeables.class.getCanonicalName());

    /**
     * Quietly close a {@link Closeable}, logging any {@link IOException}s instead of throwing them.
     *
     * @param closeable The {@link Closeable} to close quietly. If <code>null</code>, then this method is a no-op.
     */
    public static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to close resource, ignoring", e);
        }
    }
}
