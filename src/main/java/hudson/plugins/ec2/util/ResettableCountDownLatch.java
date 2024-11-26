package hudson.plugins.ec2.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class ResettableCountDownLatch {
    private final int count;
    private final AtomicReference<CountDownLatch> latchHolder = new AtomicReference<>();

    public ResettableCountDownLatch(int count) {
        this(count, true);
    }

    public ResettableCountDownLatch(int count, boolean setInitialState) {
        this.count = count;
        if (setInitialState) {
            latchHolder.set(new CountDownLatch(count));
        } else {
            latchHolder.set(new CountDownLatch(0));
        }
    }

    public void countDown() {
        latchHolder.get().countDown();
    }

    public void reset() {
        latchHolder.set(new CountDownLatch(count));
    }

    public void await() throws InterruptedException {
        latchHolder.get().await();
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return latchHolder.get().await(timeout, unit);
    }

    public long getCount() {
        return latchHolder.get().getCount();
    }
}
